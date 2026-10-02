package midbrowse.config;

import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

// Persistent app config (server URL) in a RecordStore.
public final class Config {

  private static final String RS_NAME = "midbrowse";
  private static final int REC_ID = 1;

  private static String serverUrl = "";

  private Config() {
  }

  public static synchronized String getServerUrl() {
    return serverUrl;
  }

  public static synchronized void setServerUrl(String url) {
    serverUrl = url == null ? "" : url;
  }

  public static synchronized void load() {
    RecordStore rs = null;
    try {
      rs = RecordStore.openRecordStore(RS_NAME, false);
      byte[] data = rs.getRecord(REC_ID);
      if (data != null && data.length > 0) {
        serverUrl = new String(data, "UTF-8");
      }
    } catch (RecordStoreException e) {
      // first run: keep default
    } catch (IOException e) {
      // unsupported encoding (impossible for UTF-8): keep default
    } finally {
      close(rs);
    }
  }

  public static synchronized void save() {
    RecordStore rs = null;
    try {
      byte[] data = serverUrl.getBytes("UTF-8");
      rs = RecordStore.openRecordStore(RS_NAME, true);
      try {
        rs.setRecord(REC_ID, data, 0, data.length);
      } catch (RecordStoreException add) {
        rs.addRecord(data, 0, data.length);
      }
    } catch (RecordStoreException e) {
      // persisting is best-effort
    } catch (IOException e) {
      // unsupported encoding (impossible for UTF-8)
    } finally {
      close(rs);
    }
  }

  private static void close(RecordStore rs) {
    if (rs != null) {
      try {
        rs.closeRecordStore();
      } catch (RecordStoreException e) {
        // ignore
      }
    }
  }
}
