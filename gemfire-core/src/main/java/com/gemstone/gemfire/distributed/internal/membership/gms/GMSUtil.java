package com.gemstone.gemfire.distributed.internal.membership.gms;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.GemFireConfigException;
import com.gemstone.gemfire.distributed.internal.membership.MemberAttributes;
import com.gemstone.gemfire.internal.SocketCreator;

public class GMSUtil {
  static Logger logger = Services.getLogger();
  
  public static List<InetSocketAddress> parseLocators(String locatorsString, String bindAddress) {
    InetAddress addr = null;
    
    try {
      if (bindAddress == null || bindAddress.trim().length() == 0) {
        addr = SocketCreator.getLocalHost();
      } else {
        addr = InetAddress.getByName(bindAddress);
      }
    } catch (UnknownHostException e) {
      // ignore
    }
    return parseLocators(locatorsString, addr);
  }
  
  
  public static List<InetSocketAddress> parseLocators(String locatorsString, InetAddress bindAddress) {
    List<InetSocketAddress> result=new ArrayList<InetSocketAddress>(2);
    String host;
    int port;
    boolean checkLoopback = (bindAddress != null);
    boolean isLoopback = (checkLoopback? bindAddress.isLoopbackAddress() : false);

    StringTokenizer parts=new StringTokenizer(locatorsString, ",");
    while(parts.hasMoreTokens()) {
      try {
        String str = parts.nextToken();
        host = str.substring(0, str.indexOf('['));
        int idx = host.lastIndexOf('@');
        if (idx < 0) {
          idx = host.lastIndexOf(':');
        }
        String start = host.substring(0, idx > -1 ? idx : host.length());
        if (start.indexOf(':') >= 0) { // a single numeric ipv6 address
          idx = host.lastIndexOf('@');
        }
        if (idx >= 0) {
          host = host.substring(idx+1, host.length());
        }

        int startIdx = str.indexOf('[') + 1;
        int endIdx = str.indexOf(']');
        port=Integer.parseInt(str.substring(startIdx, endIdx));
        InetSocketAddress isa = new InetSocketAddress(host, port);

        if (checkLoopback) {
          if (isLoopback && !isa.getAddress().isLoopbackAddress()) { 
            throw new GemFireConfigException("This process is attempting to join with a loopback address ("
               +bindAddress+") using a locator that does not have a local address ("
               +isa+").  On Unix this usually means that /etc/hosts is misconfigured.");
          }
        }
        result.add(isa);
      }
      catch(NumberFormatException e) {
        // this shouldln't happen because the config has already been parsed and
        // validated
      }
    }

    return result;
  }

  /**
   * replaces all occurrences of a given string in the properties argument with the
   * given value
   */
  public static String replaceStrings(String properties, String property, String value) {
    StringBuffer sb = new StringBuffer();
    int start = 0;
    int index = properties.indexOf(property);
    while (index != -1) {
      sb.append(properties.substring(start, index));
      sb.append(value);

      start = index + property.length();
      index = properties.indexOf(property, start);
    }
    sb.append(properties.substring(start));
    return sb.toString();
  }


  /**
   * Formats the bytes in a buffer into hex octets, 50 per
   * line
   */
  public static String formatBytes(byte[] buf, int startIndex, int length) {
    StringBuilder w = new StringBuilder(20000);
    int count = 0;
    for (int i=startIndex; i<length; i++, count++) {
      String s = Integer.toHexString(buf[i]&0xff);
      if (s.length() == 1) {
        w.append('0');
      }
      w.append(s).append(' ');
      if ( (count%50) == 49 ) {
        w.append("\n");
      }
    }
    return w.toString();
  }
  
  

}
