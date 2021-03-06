package com.gemstone.gemfire.distributed.internal.membership.gms.messenger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.UUID;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.gms.GMSMember;
import com.gemstone.gemfire.internal.SocketCreator;

/**
 * This is a copy of JGroups 3.6.4 IpAddress (Apache 2.0 License)
 * that is repurposed to be a Logical address so that we can
 * quickly pull a physical address out of the logical address set
 * in a message by JGroupsMessenger.
 */

public class JGAddress extends UUID {
  private static final long       serialVersionUID=-1818672332115113291L;

  // whether to show UUID info in toString()
  private final static boolean SHOW_UUIDS = Boolean.getBoolean("gemfire.show_UUIDs");

  private InetAddress             ip_addr;
  private int                     port;
  private int                     vmViewId;


  // Used only by Externalization
  public JGAddress() {
  }

  public JGAddress(InternalDistributedMember idm) {
    super();
    GMSMember mbr = (GMSMember)idm.getNetMember();
    this.mostSigBits = mbr.getUuidMSBs();
    this.leastSigBits = mbr.getUuidLSBs();
    this.ip_addr = idm.getInetAddress();
    this.port = idm.getPort();
    this.vmViewId = idm.getVmViewId();
  }


  public JGAddress(GMSMember mbr) {
    super();
    this.mostSigBits = mbr.getUuidMSBs();
    this.leastSigBits = mbr.getUuidLSBs();
    this.ip_addr = mbr.getInetAddress();
    this.port = mbr.getPort();
    this.vmViewId = mbr.getVmViewId();
  }


  public JGAddress(UUID uuid, IpAddress ipaddr) {
    super(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    ip_addr=ipaddr.getIpAddress(); port=ipaddr.getPort(); vmViewId=-1;
  }


  private void setAddressToLocalHost() {
      try {
          ip_addr=InetAddress.getLocalHost();  // get first NIC found (on multi-homed systems)
      }
      catch(Exception e) {
          ip_addr=null;
      }
      if(ip_addr == null) {
          try {
              ip_addr=InetAddress.getByName(null);
          }
          catch(UnknownHostException e) {
          }
      }
  }

  public final InetAddress  getInetAddress()               {return ip_addr;}
  public final int          getPort()                    {return port;}
  
  public final int getVmViewId() {
    return this.vmViewId;
  }
  
  protected final void setVmViewId(int id) {
    this.vmViewId = id;
  }


  public String toString() {
      StringBuilder sb=new StringBuilder();

      if(ip_addr == null)
          sb.append("<null>");
      else {
        if (SocketCreator.resolve_dns) {
          sb.append(SocketCreator.getHostName(ip_addr));
        } else {
          sb.append(ip_addr.getHostAddress());
        }
      }
      if (vmViewId >= 0) {
        sb.append("<v").append(vmViewId).append('>');
      }
      if (SHOW_UUIDS) {
        sb.append("(").append(toStringLong()).append(")");
      } else if (mostSigBits == 0 && leastSigBits == 0) {
        sb.append("(no uuid set)");
      }
      sb.append(":").append(port);
      return sb.toString();
  }


  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      try {
          readFrom(in);
      }
      catch(Exception e) {
          throw new IOException(e);
      }
  }

  public void writeExternal(ObjectOutput out) throws IOException {
      try {
          writeTo(out);
      }
      catch(Exception e) {
          throw new IOException(e);
      }
  }

  public void writeTo(DataOutput out) throws Exception {
      if(ip_addr != null) {
          byte[] address=ip_addr.getAddress();  // 4 bytes (IPv4) or 16 bytes (IPv6)
          out.writeByte(address.length); // 1 byte
          out.write(address, 0, address.length);
          if(ip_addr instanceof Inet6Address)
              out.writeInt(((Inet6Address)ip_addr).getScopeId());
      }
      else {
          out.writeByte(0);
      }
      out.writeShort(port);
      out.writeInt(vmViewId);
      out.writeLong(mostSigBits);
      out.writeLong(leastSigBits);
  }
  
  public long getUUIDMsbs() {
    return mostSigBits;
  }
  
  public long getUUIDLsbs() {
    return leastSigBits;
  }

  public void readFrom(DataInput in) throws Exception {
      int len=in.readByte();
      if(len > 0 && (len != Global.IPV4_SIZE && len != Global.IPV6_SIZE))
          throw new IOException("length has to be " + Global.IPV4_SIZE + " or " + Global.IPV6_SIZE + " bytes (was " +
                  len + " bytes)");
      byte[] a = new byte[len]; // 4 bytes (IPv4) or 16 bytes (IPv6)
      in.readFully(a);
      if(len == Global.IPV6_SIZE) {
          int scope_id=in.readInt();
          this.ip_addr=Inet6Address.getByAddress(null, a, scope_id);
      }
      else {
          this.ip_addr=InetAddress.getByAddress(a);
      }

      // changed from readShort(): we need the full 65535, with a short we'd only get up to 32K !
      port=in.readUnsignedShort();
      vmViewId = in.readInt();
      mostSigBits = in.readLong();
      leastSigBits = in.readLong();
  }

  public int size() {
      // length (1 bytes) + 4 bytes for port
      int tmp_size=Global.BYTE_SIZE+ Global.SHORT_SIZE +Global.SHORT_SIZE
          + (2*Global.LONG_SIZE);
      if(ip_addr != null) {
          // 4 bytes for IPv4, 20 for IPv6 (16 + 4 for scope-id)
          tmp_size+=(ip_addr instanceof Inet4Address)? 4 : 20;
      }
      return tmp_size;
  }

  public JGAddress copy() {
    JGAddress result = new JGAddress();
    result.mostSigBits = mostSigBits;
    result.leastSigBits = leastSigBits;
    result.ip_addr = ip_addr;
    result.port = port;
    result.vmViewId = vmViewId;
    return result;
  }

  public IpAddress asIpAddress() {
    return new IpAddress(ip_addr, port);
  }
  
}
