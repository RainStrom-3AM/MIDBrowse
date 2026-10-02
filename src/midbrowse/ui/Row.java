package midbrowse.ui;

// One visually-wrapped row on screen. Continuation rows of a wrapped link
// repeat linkIdx/url so tapping any part of the link works.
final class Row {

  final String text;
  final int linkIdx;
  final String url;

  Row(String text, int linkIdx, String url) {
    this.text = text;
    this.linkIdx = linkIdx;
    this.url = url;
  }

  boolean isLink() {
    return linkIdx >= 0 && url != null;
  }
}
