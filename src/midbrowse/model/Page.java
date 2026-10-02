package midbrowse.model;

import java.util.Vector;

// Parsed page from the desktop backend.
public final class Page {

  public String title = "";
  public String finalUrl = "";
  public String error;
  public final Vector lines = new Vector(); // Line[]
  public final Vector links = new Vector(); // String[] absolute URLs by index

  public int lineCount() {
    return lines.size();
  }

  public Line lineAt(int i) {
    return (Line) lines.elementAt(i);
  }

  public int linkCount() {
    return links.size();
  }

  public String linkAt(int i) {
    if (i < 0 || i >= links.size()) {
      return null;
    }
    return (String) links.elementAt(i);
  }
}
