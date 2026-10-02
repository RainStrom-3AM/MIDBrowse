package midbrowse.model;

// One parsed server line: either plain text (linkIdx < 0) or a link.
public final class Line {

  public final String text;
  public final int linkIdx;
  public final String url;

  private Line(String text, int linkIdx, String url) {
    this.text = text;
    this.linkIdx = linkIdx;
    this.url = url;
  }

  public static Line text(String text) {
    return new Line(text, -1, null);
  }

  public static Line link(int idx, String url, String text) {
    return new Line(text, idx, url);
  }

  public boolean isLink() {
    return linkIdx >= 0;
  }
}
