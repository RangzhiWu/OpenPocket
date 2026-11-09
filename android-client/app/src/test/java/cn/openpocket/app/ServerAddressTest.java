package cn.openpocket.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerAddressTest {
    @Test public void httpsServerAndPrivateNetworkAddressesAreConfigurable()throws Exception{
        assertEquals("https://webui.example.com",Api.validateServer("https://webui.example.com/"));
        for(String host:new String[]{"localhost","127.0.0.1","10.0.0.2","172.16.0.2","172.31.255.254","192.168.1.2","100.64.0.2","100.127.255.254","[::1]"})assertEquals("http://"+host+":3000",Api.validateServer("http://"+host+":3000/"));
    }
    @Test public void publicAndAmbiguousHttpHostsAreRejected()throws Exception{
        for(String host:new String[]{"example.com","8.8.8.8","100.63.255.255","100.128.0.1","172.32.0.1","192.169.1.2","192.168.1.999","0177.0.0.1","127.1"})reject("http://"+host);
    }
    @Test public void credentialsQueriesFragmentsPathsAndBadPortsAreRejected()throws Exception{
        for(String value:new String[]{"https://user:secret@webui.example.com","https://webui.example.com?token=test","https://webui.example.com/#token","https://webui.example.com/api","ftp://127.0.0.1","http://127.0.0.1:0","https://webui.example.com:65536",""})reject(value);
    }
    private static void reject(String value)throws Exception{try{Api.validateServer(value);fail("Unexpectedly accepted server address");}catch(AssertionError e){throw e;}catch(Exception expected){}}
}
