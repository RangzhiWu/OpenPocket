package cn.openpocket.app;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.client.Ack;
import org.json.JSONObject;
import java.net.URI;
import java.util.Collections;

/** Authenticated Socket.IO session; reconnects without cancelling server tasks. */
final class LiveSession implements AutoCloseable {
    interface Listener { void changed(boolean contentChanged); }
    private final Socket socket;
    private volatile String id="";
    LiveSession(Api api,Listener listener) {
        IO.Options options=IO.Options.builder().setPath("/ws/socket.io")
            .setTransports(new String[]{"websocket"})
            .setAuth(Collections.singletonMap("token",api.token))
            .setReconnection(true).setReconnectionDelay(1000).setReconnectionDelayMax(8000)
            .setTimeout(15000).setForceNew(true).build();
        socket=IO.socket(URI.create(api.base),options);
        socket.on(Socket.EVENT_CONNECT,args->{id=socket.id();listener.changed(false);});
        socket.on(Socket.EVENT_DISCONNECT,args->{id="";listener.changed(false);});
        socket.on(Socket.EVENT_CONNECT_ERROR,args->{id="";listener.changed(false);});
        socket.on("events",args->{
            if(args.length>1&&args[args.length-1] instanceof Ack){
                JSONObject event=args[0] instanceof JSONObject?(JSONObject)args[0]:new JSONObject();
                JSONObject data=event.optJSONObject("data");
                JSONObject reply=new JSONObject();
                try{
                    if(data!=null&&"request:terminal:state".equals(data.optString("type")))reply.put("connected",false);
                    else reply.put("error","此交互暂不支持在移动端处理，请在 PC 网页中继续。");
                }catch(org.json.JSONException ignored){}
                ((Ack)args[args.length-1]).call(reply);
            }
            listener.changed(true);
        });
    }
    void connect(){socket.connect();}
    String id(){return id;}
    boolean connected(){return socket.connected()&&!id.isEmpty();}
    @Override public void close(){socket.off();socket.disconnect();socket.close();id="";}
}
