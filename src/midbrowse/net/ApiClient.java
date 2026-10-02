package midbrowse.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

import midbrowse.model.Line;
import midbrowse.model.Page;

// Talks to the desktop backend using the MIDBROWSE/1 line protocol:
//
//   MIDBROWSE/1        (first line)
//   T\t<title>
//   U\t<final url>
//   X\t<text line>
//   L\t<idx>\t<url>\t<label>
//   E\t<message>
//   .                  (end marker)
//
// Requests: GET <server>/page?url=<enc> | <server>/search?q=<enc> | <server>/ping
// Custom header X-MIDBROWSE: 1 guards the fetch endpoints against drive-by
// open-proxy use; responses without the protocol banner are rejected.
public final class ApiClient {

  private static final String PROTO = "MIDBROWSE/1";
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();
  private static final int MAX_BODY = 512 * 1024;

  private ApiClient() {
  }

  public static Page fetchPage(String serverBase, String path) {
    Page page = new Page();
    String body;
    try {
      body = httpGet(serverBase + path);
    } catch (IOException e) {
      page.error = "Network: " + msg(e);
      return page;
    }
    parseInto(body, page);
    return page;
  }

  public static String ping(String serverBase) {
    try {
      String body = httpGet(serverBase + "/ping");
      if (body.startsWith(PROTO)) {
        return "Connected";
      }
      return "Bad response";
    } catch (IOException e) {
      return "Failed: " + msg(e);
    }
  }

  // percent-encode for query strings (UTF-8)
  public static String encode(String s) {
    if (s == null) {
      return "";
    }
    byte[] bytes;
    try {
      bytes = s.getBytes("UTF-8");
    } catch (IOException e) {
      bytes = s.getBytes(); // fallback: platform encoding
    }
    StringBuffer sb = new StringBuffer(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      int c = bytes[i] & 0xff;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
        sb.append((char) c);
      } else {
        sb.append('%');
        sb.append(HEX[(c >> 4) & 0xf]);
        sb.append(HEX[c & 0xf]);
      }
    }
    return sb.toString();
  }

  private static String httpGet(String url) throws IOException {
    HttpConnection conn = null;
    InputStream in = null;
    try {
      conn = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
      conn.setRequestProperty("User-Agent", "MIDBrowse/0.1 (J2ME)");
      conn.setRequestProperty("X-MIDBROWSE", "1");
      conn.setRequestProperty("Accept", "*/*");
      int code = conn.getResponseCode();
      if (code >= 400) {
        throw new IOException("HTTP " + code);
      }
      in = conn.openInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[1024];
      int total = 0;
      int n;
      while ((n = in.read(buf)) > 0) {
        total += n;
        if (total > MAX_BODY) {
          throw new IOException("Response too large");
        }
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), "UTF-8");
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          // ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
  }

  private static void parseInto(String body, Page page) {
    if (body == null || body.length() == 0) {
      page.error = "Empty response";
      return;
    }
    if (!body.startsWith(PROTO)) {
      page.error = "Not a MIDBrowse server";
      return;
    }
    int pos = PROTO.length();
    boolean first = true;
    int linkIdx = 0;
    while (pos <= body.length()) {
      int nl = body.indexOf('\n', pos);
      if (nl < 0) {
        nl = body.length();
      }
      String line = body.substring(pos, nl);
      pos = nl + 1;
      if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
        line = line.substring(0, line.length() - 1);
      }
      if (first) {
        first = false;
        if (pos > body.length()) {
          break;
        }
        continue;
      }
      if (line.equals(".")) {
        break;
      }
      if (line.length() < 2 || line.charAt(1) != '\t') {
        if (pos > body.length()) {
          break;
        }
        continue;
      }
      char kind = line.charAt(0);
      String rest = line.substring(2);
      if (kind == 'T') {
        page.title = rest;
      } else if (kind == 'U') {
        page.finalUrl = rest;
      } else if (kind == 'E') {
        page.error = rest;
      } else if (kind == 'X') {
        page.lines.addElement(Line.text(rest));
      } else if (kind == 'L') {
        int t1 = rest.indexOf('\t');
        int t2 = t1 < 0 ? -1 : rest.indexOf('\t', t1 + 1);
        if (t1 > 0 && t2 > t1) {
          String url = rest.substring(t1 + 1, t2);
          String label = rest.substring(t2 + 1);
          page.links.addElement(url);
          page.lines.addElement(Line.link(linkIdx, url, label));
          linkIdx++;
        }
      }
      if (pos > body.length()) {
        break;
      }
    }
    if (page.error == null && page.lines.size() == 0) {
      page.error = "Empty page";
    }
  }

  private static String msg(Exception e) {
    String m = e.getMessage();
    if (m == null || m.length() == 0) {
      return e.getClass().getName();
    }
    return m;
  }
}
