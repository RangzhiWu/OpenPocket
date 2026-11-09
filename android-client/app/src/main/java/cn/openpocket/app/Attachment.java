package cn.openpocket.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONObject;

final class Attachment {
    final Uri uri;
    final String name,mime;
    final long size;
    String status="准备上传",error="";
    JSONObject uploaded;
    boolean busy=true;
    Attachment(ContentResolver resolver,Uri uri) {
        this.uri=uri;String name="附件",mime=resolver.getType(uri);long size=-1;
        try(Cursor cursor=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s);}
        }catch(Exception ignored){}
        this.name=name;this.mime=mime==null?"application/octet-stream":mime;this.size=size;
    }
    JSONObject descriptor(JSONObject file) throws Exception {
        String id=file.optString("id");if(id.isEmpty())throw new Exception("服务器没有返回附件编号");
        return new JSONObject().put("id",id).put("type",mime.startsWith("image/")?"image":"file").put("name",name)
            .put("url","/api/v1/files/"+Api.query(id)+"/content").put("file",file).put("size",size).put("content_type",mime).put("status","uploaded");
    }
}
