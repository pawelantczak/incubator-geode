/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.internal.offheap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.logging.LogService;
import com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;

/**
 * This allocator is somewhat like an Arena allocator.
 * We start out with an array of multiple large chunks of memory.
 * We also keep lists of any chunk that have been allocated and freed.
 * An allocation will always try to find a chunk in a free list that is a close fit to the requested size.
 * If no close fits exist then it allocates the next slice from the front of one the original large chunks.
 * If we can not find enough free memory then all the existing free memory is compacted.
 * If we still do not have enough to make the allocation an exception is thrown.
 * 
 * @author darrel
 * @author Kirk Lund
 * @since 9.0
 */
public final class SimpleMemoryAllocatorImpl implements MemoryAllocator, MemoryInspector {

  static final Logger logger = LogService.getLogger();
  
  public static final String FREE_OFF_HEAP_MEMORY_PROPERTY = "gemfire.free-off-heap-memory";
  
  /**
   * How many extra allocations to do for each actual slab allocation.
   * Is this really a good idea?
   */
  public static final int BATCH_SIZE = Integer.getInteger("gemfire.OFF_HEAP_BATCH_ALLOCATION_SIZE", 1);
  /**
   * Every allocated chunk smaller than TINY_MULTIPLE*TINY_FREE_LIST_COUNT will allocate a chunk of memory that is a multiple of this value.
   * Sizes are always rounded up to the next multiple of this constant
   * so internal fragmentation will be limited to TINY_MULTIPLE-1 bytes per allocation
   * and on average will be TINY_MULTIPLE/2 given a random distribution of size requests.
   */
  public final static int TINY_MULTIPLE = Integer.getInteger("gemfire.OFF_HEAP_ALIGNMENT", 8);
  /**
   * Number of free lists to keep for tiny allocations.
   */
  public final static int TINY_FREE_LIST_COUNT = Integer.getInteger("gemfire.OFF_HEAP_FREE_LIST_COUNT", 16384);
  public final static int MAX_TINY = TINY_MULTIPLE*TINY_FREE_LIST_COUNT;
  public final static int HUGE_MULTIPLE = 256;
  
  volatile OffHeapMemoryStats stats;
  
  volatile OutOfOffHeapMemoryListener ooohml;
  
  /** The MemoryChunks that this allocator is managing by allocating smaller chunks of them.
   * The contents of this array never change.
   */
  private final UnsafeMemoryChunk[] slabs;
  private final long totalSlabSize;
  private final int largestSlab;
  
  public final FreeListManager freeList;
  
  private volatile MemoryUsageListener[] memoryUsageListeners = new MemoryUsageListener[0];
  
  private static SimpleMemoryAllocatorImpl singleton = null;
  private static final AtomicReference<Thread> asyncCleanupThread = new AtomicReference<Thread>();
  final ChunkFactory chunkFactory;
  
  public static SimpleMemoryAllocatorImpl getAllocator() {
    SimpleMemoryAllocatorImpl result = singleton;
    if (result == null) {
      throw new CacheClosedException("Off Heap memory allocator does not exist.");
    }
    return result;
  }

  private static final boolean PRETOUCH = Boolean.getBoolean("gemfire.OFF_HEAP_PRETOUCH_PAGES");
  static final int OFF_HEAP_PAGE_SIZE = Integer.getInteger("gemfire.OFF_HEAP_PAGE_SIZE", UnsafeMemoryChunk.getPageSize());
  private static final boolean DO_EXPENSIVE_VALIDATION = Boolean.getBoolean("gemfire.OFF_HEAP_DO_EXPENSIVE_VALIDATION");;
  
  public static MemoryAllocator create(OutOfOffHeapMemoryListener ooohml, OffHeapMemoryStats stats, LogWriter lw, int slabCount, long offHeapMemorySize, long maxSlabSize) {
    SimpleMemoryAllocatorImpl result = singleton;
    boolean created = false;
    try {
    if (result != null) {
      result.reuse(ooohml, lw, stats, offHeapMemorySize);
      lw.config("Reusing " + result.getTotalMemory() + " bytes of off-heap memory. The maximum size of a single off-heap object is " + result.largestSlab + " bytes.");
      created = true;
      LifecycleListener.invokeAfterReuse(result);
    } else {
      // allocate memory chunks
      //SimpleMemoryAllocatorImpl.cleanupPreviousAllocator();
      lw.config("Allocating " + offHeapMemorySize + " bytes of off-heap memory. The maximum size of a single off-heap object is " + maxSlabSize + " bytes.");
      UnsafeMemoryChunk[] slabs = new UnsafeMemoryChunk[slabCount];
      long uncreatedMemory = offHeapMemorySize;
      for (int i=0; i < slabCount; i++) {
        try {
        if (uncreatedMemory >= maxSlabSize) {
          slabs[i] = new UnsafeMemoryChunk((int) maxSlabSize);
          uncreatedMemory -= maxSlabSize;
        } else {
          // the last slab can be smaller then maxSlabSize
          slabs[i] = new UnsafeMemoryChunk((int) uncreatedMemory);
        }
        } catch (OutOfMemoryError err) {
          if (i > 0) {
            lw.severe("Off-heap memory creation failed after successfully allocating " + (i*maxSlabSize) + " bytes of off-heap memory.");
          }
          for (int j=0; j < i; j++) {
            if (slabs[j] != null) {
              slabs[j].release();
            }
          }
          throw err;
        }
      }

      result = new SimpleMemoryAllocatorImpl(ooohml, stats, slabs);
      created = true;
      singleton = result;
      LifecycleListener.invokeAfterCreate(result);
    }
    } finally {
      if (!created) {
        stats.close();
        ooohml.close();
      }
    }
    return result;
  }
  // for unit tests
  public static SimpleMemoryAllocatorImpl create(OutOfOffHeapMemoryListener oooml, OffHeapMemoryStats stats, UnsafeMemoryChunk[] slabs) {
    SimpleMemoryAllocatorImpl result = new SimpleMemoryAllocatorImpl(oooml, stats, slabs);
    singleton = result;
    LifecycleListener.invokeAfterCreate(result);
    return result;
  }
  
  private void reuse(OutOfOffHeapMemoryListener oooml, LogWriter lw, OffHeapMemoryStats newStats, long offHeapMemorySize) {
    if (isClosed()) {
      throw new IllegalStateException("Can not reuse a closed off-heap memory manager.");
    }
    if (oooml == null) {
      throw new IllegalArgumentException("OutOfOffHeapMemoryListener is null");
    }
    if (getTotalMemory() != offHeapMemorySize) {
      lw.warning("Using " + getTotalMemory() + " bytes of existing off-heap memory instead of the requested " + offHeapMemorySize);
    }
    this.ooohml = oooml;
    newStats.initialize(this.stats);
    this.stats = newStats;
  }

  public static void cleanupPreviousAllocator() {
    Thread t = asyncCleanupThread.getAndSet(null);
    if (t != null) {
//      try {
//        // HACK to see if a delay fixes bug 47883
//        Thread.sleep(3000);
//      } catch (InterruptedException ignore) {
//      }
      t.interrupt();
      try {
        t.join(FREE_PAUSE_MILLIS);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  private SimpleMemoryAllocatorImpl(final OutOfOffHeapMemoryListener oooml, final OffHeapMemoryStats stats, final UnsafeMemoryChunk[] slabs) {
    if (oooml == null) {
      throw new IllegalArgumentException("OutOfOffHeapMemoryListener is null");
    }
    if (TINY_MULTIPLE <= 0 || (TINY_MULTIPLE & 3) != 0) {
      throw new IllegalStateException("gemfire.OFF_HEAP_ALIGNMENT must be a multiple of 8.");
    }
    if (TINY_MULTIPLE > 256) {
      // this restriction exists because of the dataSize field in the object header.
      throw new IllegalStateException("gemfire.OFF_HEAP_ALIGNMENT must be <= 256 and a multiple of 8.");
    }
    if (BATCH_SIZE <= 0) {
      throw new IllegalStateException("gemfire.OFF_HEAP_BATCH_ALLOCATION_SIZE must be >= 1.");
    }
    if (TINY_FREE_LIST_COUNT <= 0) {
      throw new IllegalStateException("gemfire.OFF_HEAP_FREE_LIST_COUNT must be >= 1.");
    }
    assert HUGE_MULTIPLE <= 256;
    
    this.ooohml = oooml;
    this.stats = stats;
    this.slabs = slabs;
    if(GemFireCacheImpl.sqlfSystem()) {
      throw new IllegalStateException("offheap sqlf not supported");
//       String provider = GemFireCacheImpl.SQLF_FACTORY_PROVIDER;
//       try {
//         Class<?> factoryProvider = Class.forName(provider);
//         Method method = factoryProvider.getDeclaredMethod("getChunkFactory");        
//         this.chunkFactory  = (ChunkFactory)method.invoke(null, (Object [])null);
//       }catch (Exception e) {
//         throw new IllegalStateException("Exception in obtaining ChunkFactory class",  e);
//       }

    }else {
      
      this.chunkFactory = new GemFireChunkFactory();
    }
    
    if (PRETOUCH) {
      final int tc;
      if (Runtime.getRuntime().availableProcessors() > 1) {
        tc = Runtime.getRuntime().availableProcessors() / 2;
      } else {
        tc = 1;
      }
      Thread[] threads = new Thread[tc];
      for (int i=0; i < tc; i++) {
        final int threadId = i;
        threads[i] = new Thread(new Runnable() {
          @Override
          public void run() {
            for (int slabId=threadId; slabId < slabs.length; slabId+=tc) {
              final int slabSize = slabs[slabId].getSize();
              for (int pageId=0; pageId < slabSize; pageId+=OFF_HEAP_PAGE_SIZE) {
                slabs[slabId].writeByte(pageId, (byte) 0);
              }
            }
          }
        });
        threads[i].start();
      }
      for (int i=0; i < tc; i++) {
        try {
          threads[i].join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    //OSProcess.printStacks(0, InternalDistributedSystem.getAnyInstance().getLogWriter(), false);
    this.stats.setFragments(slabs.length);
    largestSlab = slabs[0].getSize();
    this.stats.setLargestFragment(largestSlab);
    long total = 0;
    for (int i=0; i < slabs.length; i++) {
      //debugLog("slab"+i + " @" + Long.toHexString(slabs[i].getMemoryAddress()), false);
      //UnsafeMemoryChunk.clearAbsolute(slabs[i].getMemoryAddress(), slabs[i].getSize()); // HACK to see what this does to bug 47883
      total += slabs[i].getSize();
    }
    totalSlabSize = total;
    this.stats.incMaxMemory(this.totalSlabSize);
    this.stats.incFreeMemory(this.totalSlabSize);
    
    this.freeList = new FreeListManager(this);
  }
  
  public List<Chunk> getLostChunks() {
    List<Chunk> liveChunks = this.freeList.getLiveChunks();
    List<Chunk> regionChunks = getRegionLiveChunks();
    Set liveChunksSet = new HashSet(liveChunks);
    Set regionChunksSet = new HashSet(regionChunks);
    liveChunksSet.removeAll(regionChunksSet);
    return new ArrayList<Chunk>(liveChunksSet);
  }
  
  /**
   * Returns a possibly empty list that contains all the Chunks used by regions.
   */
  private List<Chunk> getRegionLiveChunks() {
    ArrayList<Chunk> result = new ArrayList<Chunk>();
    GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
    if (gfc != null) {
      Iterator rootIt = gfc.rootRegions().iterator();
      while (rootIt.hasNext()) {
        Region rr = (Region) rootIt.next();
        getRegionLiveChunks(rr, result);
        Iterator srIt = rr.subregions(true).iterator();
        while (srIt.hasNext()) {
          Region sr = (Region)srIt.next();
          getRegionLiveChunks(sr, result);
        }
      }
    }
    return result;
  }

  private void getRegionLiveChunks(Region r, List<Chunk> result) {
    if (r.getAttributes().getOffHeap()) {

      if (r instanceof PartitionedRegion) {
        PartitionedRegionDataStore prs = ((PartitionedRegion) r).getDataStore();
        if (prs != null) {
          Set<BucketRegion> brs = prs.getAllLocalBucketRegions();
          if (brs != null) {
            for (BucketRegion br : brs) {
              if (br != null && !br.isDestroyed()) {
                this.basicGetRegionLiveChunks(br, result);
              }

            }
          }
        }
      } else {
        this.basicGetRegionLiveChunks((LocalRegion) r, result);
      }

    }

  }
  
  private void basicGetRegionLiveChunks(LocalRegion r, List<Chunk> result) {
    for (Object key : r.keySet()) {
      RegionEntry re = ((LocalRegion) r).getRegionEntry(key);
      if (re != null) {
        /**
         * value could be GATEWAY_SENDER_EVENT_IMPL_VALUE or region entry value.
         */
        @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
        Object value = re._getValue();
        if (value instanceof Chunk) {
          result.add((Chunk) value);
        }
      }
    }
  }

  @Override
  public MemoryChunk allocate(int size, ChunkType chunkType) {
    //System.out.println("allocating " + size);
    Chunk result = this.freeList.allocate(size, chunkType);
    //("allocated off heap object of size " + size + " @" + Long.toHexString(result.getMemoryAddress()), true);
    if (ReferenceCountHelper.trackReferenceCounts()) {
      ReferenceCountHelper.refCountChanged(result.getMemoryAddress(), false, 1);
    }
    return result;
  }
  
  @SuppressWarnings("unused")
  public static void debugLog(String msg, boolean logStack) {
    if (logStack) {
      logger.info(msg, new RuntimeException(msg));
    } else {
      logger.info(msg);
    }
  }
  
  @Override
  public StoredObject allocateAndInitialize(byte[] v, boolean isSerialized, boolean isCompressed, ChunkType chunkType) {
    long addr = OffHeapRegionEntryHelper.encodeDataAsAddress(v, isSerialized, isCompressed);
    if (addr != 0L) {
      return new DataAsAddress(addr);
    }
    if (chunkType == null) {
      chunkType = GemFireChunk.TYPE;
    }

    Chunk result = this.freeList.allocate(v.length, chunkType);
    //debugLog("allocated off heap object of size " + v.length + " @" + Long.toHexString(result.getMemoryAddress()), true);
    //debugLog("allocated off heap object of size " + v.length + " @" + Long.toHexString(result.getMemoryAddress()) +  "chunkSize=" + result.getSize() + " isSerialized=" + isSerialized + " v=" + Arrays.toString(v), true);
    if (ReferenceCountHelper.trackReferenceCounts()) {
      ReferenceCountHelper.refCountChanged(result.getMemoryAddress(), false, 1);
    }
    assert result.getChunkType() == chunkType: "chunkType=" + chunkType + " getChunkType()=" + result.getChunkType();
    result.setSerializedValue(v);
    result.setSerialized(isSerialized);
    result.setCompressed(isCompressed);
    return result;
  }
  
  @Override
  public long getFreeMemory() {
    return this.freeList.getFreeMemory();
  }

  @Override
  public long getUsedMemory() {
    return this.freeList.getUsedMemory();
  }

  @Override
  public long getTotalMemory() {
    return totalSlabSize;
  }
  
  @Override
  public void close() {
    try {
      LifecycleListener.invokeBeforeClose(this);
    } finally {
      this.ooohml.close();
      if (Boolean.getBoolean(FREE_OFF_HEAP_MEMORY_PROPERTY)) {
        realClose();
      }
    }
  }
  
  public static void freeOffHeapMemory() {
    SimpleMemoryAllocatorImpl ma = singleton;
    if (ma != null) {
      ma.realClose();
    }
  }
  
  private void realClose() {
    // Removing this memory immediately can lead to a SEGV. See 47885.
    if (setClosed()) {
      freeSlabsAsync(this.slabs);
      this.stats.close();
      singleton = null;
    }
  }
  
  private final AtomicBoolean closed = new AtomicBoolean();
  private boolean isClosed() {
    return this.closed.get();
  }
  /**
   * Returns true if caller is the one who should close; false if some other thread
   * is already closing.
   */
  private boolean setClosed() {
    return this.closed.compareAndSet(false, true);
  }
  

  private static final int FREE_PAUSE_MILLIS = Integer.getInteger("gemfire.OFF_HEAP_FREE_PAUSE_MILLIS", 90000);

  
  
  private static void freeSlabsAsync(final UnsafeMemoryChunk[] slabs) {
    //debugLog("called freeSlabsAsync", false);
    // since we no longer free off-heap memory on every cache close
    // and production code does not free it but instead reuses it
    // we should be able to free it sync.
    // If it turns out that it does need to be async then we need
    // to make sure we call cleanupPreviousAllocator.
    for (int i=0; i < slabs.length; i++) {
      slabs[i].release();
    }
//    Thread t = new Thread(new Runnable() {
//      @Override
//      public void run() {
//        // pause this many millis before freeing the slabs.
//        try {
//          Thread.sleep(FREE_PAUSE_MILLIS);
//        } catch (InterruptedException ignore) {
//          // If we are interrupted we should wakeup
//          // and free our slabs.
//        }
//        //debugLog("returning offheap memory to OS", false);
//        for (int i=0; i < slabs.length; i++) {
//          slabs[i].free();
//        }
//        //debugLog("returned offheap memory to OS", false);
//        asyncCleanupThread.compareAndSet(Thread.currentThread(), null);
//      }
//    }, "asyncSlabDeallocator");
//    t.setDaemon(true);
//    t.start();
//    asyncCleanupThread.set(t);    
  }
  
  void freeChunk(long addr) {
    this.freeList.free(addr);
  }
  
  protected UnsafeMemoryChunk[] getSlabs() {
    return this.slabs;
  }
  
  /**
   * Return the slabId of the slab that contains the given addr.
   */
  protected int findSlab(long addr) {
    for (int i=0; i < this.slabs.length; i++) {
      UnsafeMemoryChunk slab = this.slabs[i];
      long slabAddr = slab.getMemoryAddress();
      if (addr >= slabAddr) {
        if (addr < slabAddr + slab.getSize()) {
          return i;
        }
      }
    }
    throw new IllegalStateException("could not find a slab for addr " + addr);
  }
  
  public OffHeapMemoryStats getStats() {
    return this.stats;
  }
  
  public ChunkFactory getChunkFactory() {
    return this.chunkFactory;
  }

  @Override
  public void addMemoryUsageListener(final MemoryUsageListener listener) {
    synchronized (this.memoryUsageListeners) {
      final MemoryUsageListener[] newMemoryUsageListeners = Arrays.copyOf(this.memoryUsageListeners, this.memoryUsageListeners.length + 1);
      newMemoryUsageListeners[this.memoryUsageListeners.length] = listener;
      this.memoryUsageListeners = newMemoryUsageListeners;
    }
  }
  
  @Override
  public void removeMemoryUsageListener(final MemoryUsageListener listener) {
    synchronized (this.memoryUsageListeners) {
      int listenerIndex = -1;
      for (int i = 0; i < this.memoryUsageListeners.length; i++) {
        if (this.memoryUsageListeners[i] == listener) {
          listenerIndex = i;
          break;
        }
      }

      if (listenerIndex != -1) {
        final MemoryUsageListener[] newMemoryUsageListeners = new MemoryUsageListener[this.memoryUsageListeners.length - 1];
        System.arraycopy(this.memoryUsageListeners, 0, newMemoryUsageListeners, 0, listenerIndex);
        System.arraycopy(this.memoryUsageListeners, listenerIndex + 1, newMemoryUsageListeners, listenerIndex,
            this.memoryUsageListeners.length - listenerIndex - 1);
        this.memoryUsageListeners = newMemoryUsageListeners;
      }
    }
  }
  
  void notifyListeners() {
    final MemoryUsageListener[] savedListeners = this.memoryUsageListeners;
    
    if (savedListeners.length == 0) {
      return;
    }

    final long bytesUsed = getUsedMemory();
    for (int i = 0; i < savedListeners.length; i++) {
      savedListeners[i].updateMemoryUsed(bytesUsed);
    }
  }
  
  static void validateAddress(long addr) {
    validateAddressAndSize(addr, -1);
  }
  
  static void validateAddressAndSize(long addr, int size) {
    // if the caller does not have a "size" to provide then use -1
    if ((addr & 7) != 0) {
      StringBuilder sb = new StringBuilder();
      sb.append("address was not 8 byte aligned: 0x").append(Long.toString(addr, 16));
      SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.singleton;
      if (ma != null) {
        sb.append(". Valid addresses must be in one of the following ranges: ");
        for (int i=0; i < ma.slabs.length; i++) {
          long startAddr = ma.slabs[i].getMemoryAddress();
          long endAddr = startAddr + ma.slabs[i].getSize();
          sb.append("[").append(Long.toString(startAddr, 16)).append("..").append(Long.toString(endAddr, 16)).append("] ");
        }
      }
      throw new IllegalStateException(sb.toString());
    }
    if (addr >= 0 && addr < 1024) {
      throw new IllegalStateException("addr was smaller than expected 0x" + addr);
    }
    validateAddressAndSizeWithinSlab(addr, size);
  }

  static void validateAddressAndSizeWithinSlab(long addr, int size) {
    if (DO_EXPENSIVE_VALIDATION) {
      SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.singleton;
      if (ma != null) {
        for (int i=0; i < ma.slabs.length; i++) {
          if (ma.slabs[i].getMemoryAddress() <= addr && addr < (ma.slabs[i].getMemoryAddress() + ma.slabs[i].getSize())) {
            // validate addr + size is within the same slab
            if (size != -1) { // skip this check if size is -1
              if (!(ma.slabs[i].getMemoryAddress() <= (addr+size-1) && (addr+size-1) < (ma.slabs[i].getMemoryAddress() + ma.slabs[i].getSize()))) {
                throw new IllegalStateException(" address 0x" + Long.toString(addr+size-1, 16) + " does not address the original slab memory");
              }
            }
            return;
          }
        }
        throw new IllegalStateException(" address 0x" + Long.toString(addr, 16) + " does not address the original slab memory");
      }
    }
  }
  
  /** The inspection snapshot for MemoryInspector */
  private List<MemoryBlock> memoryBlocks;
  
  @Override
  public MemoryInspector getMemoryInspector() {
    return this;
  }
  
  @Override
  public synchronized void clearInspectionSnapshot() {
    this.memoryBlocks = null;
  }
  
  @Override
  public synchronized void createInspectionSnapshot() {
    List<MemoryBlock> value = this.memoryBlocks;
    if (value == null) {
      value = getOrderedBlocks();
      this.memoryBlocks = value;
    }
  }

  synchronized List<MemoryBlock> getInspectionSnapshot() {
    List<MemoryBlock> value = this.memoryBlocks;
    if (value == null) {
      return Collections.<MemoryBlock>emptyList();
    } else {
      return value;
    }
  }
  
  @Override
  public synchronized List<MemoryBlock> getOrphans() {
    List<Chunk> liveChunks = this.freeList.getLiveChunks();
    List<Chunk> regionChunks = getRegionLiveChunks();
    liveChunks.removeAll(regionChunks);
    List<MemoryBlock> orphans = new ArrayList<MemoryBlock>();
    for (Chunk chunk: liveChunks) {
      orphans.add(new MemoryBlockNode(this, chunk));
    }
    Collections.sort(orphans, 
        new Comparator<MemoryBlock>() {
          @Override
          public int compare(MemoryBlock o1, MemoryBlock o2) {
            return Long.valueOf(o1.getMemoryAddress()).compareTo(o2.getMemoryAddress());
          }
    });
    //this.memoryBlocks = new WeakReference<List<MemoryBlock>>(orphans);
    return orphans;
  }
  
  @Override
  public MemoryBlock getFirstBlock() {
    final List<MemoryBlock> value = getInspectionSnapshot();
    if (value.isEmpty()) {
      return null;
    } else {
      return value.get(0);
    }
  }
  
  @Override
  public List<MemoryBlock> getAllBlocks() {
    return getOrderedBlocks();
  }
  
  @Override
  public List<MemoryBlock> getAllocatedBlocks() {
    return this.freeList.getAllocatedBlocks();
  }

  @Override
  public List<MemoryBlock> getDeallocatedBlocks() {
    return null;
  }

  @Override
  public List<MemoryBlock> getUnusedBlocks() {
    return null;
  }
  
  @Override
  public MemoryBlock getBlockContaining(long memoryAddress) {
    return null;
  }
  
  @Override
  public MemoryBlock getBlockAfter(MemoryBlock block) {
    if (block == null) {
      return null;
    }
    List<MemoryBlock> blocks = getInspectionSnapshot();
    int nextBlock = blocks.indexOf(block) + 1;
    if (nextBlock > 0 && blocks.size() > nextBlock) {
      return blocks.get(nextBlock);
    } else {
      return null;
    }
  }

  private List<MemoryBlock> getOrderedBlocks() {
    return this.freeList.getOrderedBlocks();
  }
  
  /*
   * Set this to "true" to perform data integrity checks on allocated and reused Chunks.  This may clobber 
   * performance so turn on only when necessary.
   */
  final boolean validateMemoryWithFill = Boolean.getBoolean("gemfire.validateOffHeapWithFill");
  
}
