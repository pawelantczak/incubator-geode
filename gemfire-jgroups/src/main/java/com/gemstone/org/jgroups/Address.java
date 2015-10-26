/** Notice of modification as required by the LGPL
 *  This file was modified by Gemstone Systems Inc. on
 *  $Date$
 **/
// $Id: Address.java,v 1.4 2005/07/17 11:38:05 chrislott Exp $

package com.gemstone.org.jgroups;

import com.gemstone.org.jgroups.util.Streamable;

import java.io.Externalizable;



/**
 * Abstract address. Used to identify members on a group to send messages to.
 * Addresses are mostly generated by the bottom-most (transport) layers (e.g. UDP, TCP, LOOPBACK).
 * @author Bela Ban
 */
public interface Address extends Externalizable, Streamable, Comparable, Cloneable { // todo: remove Externalizable

    /**
     * Checks whether this is an address that represents multiple destinations;
     * e.g., a class D address in the Internet.
     * @return true if this is a multicast address, false if it is a unicast address
     */
    boolean  isMulticastAddress();

    /** Returns serialized size of this address */
    int size(short version);
    
    /** Returns true if this member could become the group coordinator */
    boolean preferredForCoordinator();
    
    /** Returns true if this member has split-brain detection enabled */
    boolean splitBrainEnabled();

    /**
     * Returns the actual version ordinal of the member. Note that this can be
     * something higher than that of this member.
     */
    short getVersionOrdinal();
    
    int getBirthViewId();

}