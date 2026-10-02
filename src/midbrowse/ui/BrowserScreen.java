package midbrowse.ui;

import java.util.Vector;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

import midbrowse.MIDBrowse;
import midbrowse.config.Config;
import midbrowse.model.Line;
import midbrowse.model.Page;
import midbrowse.net.ApiClient;

// Canvas browser: renders the backend's simplified page (text + links),
// cursor navigation, digit-jump to links, menu-driven URL/search/server input.
public class BrowserScreen extends Canvas implements CommandListener {

  private static final int MODE_OPEN = 0;
  private static final int MODE_SEARCH = 1;
  private static final int MODE_SERVER = 2;

  private static final int COLOR_BG = 0xFFFFFF;
  private static final int COLOR_BAR = 0x1F3B57;
  private static final int COLOR_TEXT = 0x111111;
  private static final int COLOR_LINK = 0x1144AA;
  private static final int COLOR_CURSOR_BG = 0xCCCCDD;
  private static final int COLOR_LINK_CURSOR_BG = 0x3366BB;
  private static final int COLOR_CURSOR_TEXT = 0x000000;
  private static final int COLOR_LINK_CURSOR_TEXT = 0xFFFFFF;
  private static final int COLOR_MUTED = 0x666666;

  private final MIDBrowse midlet;
  private final Display display;
  private final Font font;
  private final int rowH;
  private final int barH;

  private Page page;
  private Vector rows = new Vector(); // Row[]
  private String currentPath = "";
  private String currentUrl = "";
  private String status = "";
  private boolean loading;
  private int topRow;
  private int cursorRow;
  private final Vector history = new Vector(); // String[] previous paths
  private int requestId;

  private final Command menuCmd = new Command("Menu", Command.SCREEN, 1);
  private final Command backCmd = new Command("Back", Command.BACK, 2);
  private final Command goCmd = new Command("Go", Command.OK, 1);
  private final Command cancelCmd = new Command("Cancel", Command.BACK, 2);

  private TextBox inputBox;
  private List menuList;
  private int inputMode;

  public BrowserScreen(MIDBrowse midlet) {
    this.midlet = midlet;
    display = Display.getDisplay(midlet);
    font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    rowH = font.getHeight() + 2;
    barH = font.getHeight() + 4;
    addCommand(menuCmd);
    addCommand(backCmd);
    setCommandListener(this);
  }

  // ------------------------------------------------------------- lifecycle

  public void begin() {
    if (Config.getServerUrl().length() == 0) {
      promptServer();
    } else {
      openHome();
    }
  }

  public void shutdown() {
    requestId++; // invalidate any in-flight result
  }

  // ------------------------------------------------------------ menu / input

  public void commandAction(Command c, Displayable d) {
    if (d == inputBox) {
      if (c == goCmd) {
        handleGo();
      } else if (c == cancelCmd) {
        display.setCurrent(this);
      }
      return;
    }
    if (d == menuList) {
      if (c == List.SELECT_COMMAND) {
        int idx = menuList.getSelectedIndex();
        display.setCurrent(this);
        handleMenuItem(idx);
      } else if (c == cancelCmd) {
        display.setCurrent(this);
      }
      return;
    }
    if (c == menuCmd) {
      showMenu();
    } else if (c == backCmd) {
      goBack();
    }
  }

  private void showMenu() {
    menuList = new List("MIDBrowse", List.IMPLICIT);
    menuList.append("Open URL...", null);
    menuList.append("Search...", null);
    menuList.append("Reload", null);
    menuList.append("Back", null);
    menuList.append("Server address...", null);
    menuList.append("Test connection", null);
    menuList.append("Exit", null);
    menuList.addCommand(cancelCmd);
    menuList.setCommandListener(this);
    display.setCurrent(menuList);
  }

  private void handleMenuItem(int idx) {
    switch (idx) {
      case 0:
        openInput(MODE_OPEN, "Open URL / search", currentUrl);
        break;
      case 1:
        openInput(MODE_SEARCH, "Search the web", "");
        break;
      case 2:
        reload();
        break;
      case 3:
        goBack();
        break;
      case 4:
        openInput(MODE_SERVER, "Server (http://ip:8765)", Config.getServerUrl());
        break;
      case 5:
        testConnection();
        break;
      case 6:
        midlet.destroyApp(true);
        midlet.notifyDestroyed();
        break;
      default:
        break;
    }
  }

  private void openInput(int mode, String title, String initial) {
    inputMode = mode;
    if (inputBox == null) {
      inputBox = new TextBox("MIDBrowse", "", 512, TextField.ANY);
      inputBox.addCommand(goCmd);
      inputBox.addCommand(cancelCmd);
      inputBox.setCommandListener(this);
    }
    inputBox.setTitle(title);
    inputBox.setString(initial == null ? "" : initial);
    display.setCurrent(inputBox);
  }

  private void promptServer() {
    openInput(MODE_SERVER, "Server (http://ip:8765)", "");
  }

  private void handleGo() {
    String s = inputBox.getString().trim();
    display.setCurrent(this);
    if (s.length() == 0) {
      if (inputMode == MODE_SERVER) {
        status = "Set a server first";
        repaint();
      }
      return;
    }
    if (inputMode == MODE_SERVER) {
      String lower = s.toLowerCase();
      if (lower.indexOf("http://") != 0 && lower.indexOf("https://") != 0) {
        s = "http://" + s;
      }
      while (s.endsWith("/")) {
        s = s.substring(0, s.length() - 1);
      }
      Config.setServerUrl(s);
      Config.save();
      status = "Server saved";
      repaint();
      openHome();
    } else if (inputMode == MODE_SEARCH) {
      doSearch(s);
    } else {
      navigate(s);
    }
  }

  // Decide from free text: URL vs search query.
  private void navigate(String s) {
    String lower = s.toLowerCase();
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
      openUrl(s);
    } else if (s.indexOf(' ') >= 0 || s.indexOf('.') < 0) {
      doSearch(s);
    } else {
      openUrl("http://" + s);
    }
  }

  // ---------------------------------------------------------------- loading

  private void openHome() {
    loadPath("/page", false);
  }

  private void openUrl(String url) {
    loadPath("/page?url=" + ApiClient.encode(url), true);
  }

  private void doSearch(String q) {
    loadPath("/search?q=" + ApiClient.encode(q), true);
  }

  private void reload() {
    if (currentPath.length() > 0) {
      loadPath(currentPath, false);
    }
  }

  private void goBack() {
    if (history.size() == 0) {
      status = "No previous page";
      repaint();
      return;
    }
    String path = (String) history.elementAt(history.size() - 1);
    history.removeElementAt(history.size() - 1);
    loadPath(path, false);
  }

  private void loadPath(final String path, boolean pushHistory) {
    if (Config.getServerUrl().length() == 0) {
      promptServer();
      return;
    }
    if (pushHistory && currentPath.length() > 0 && !currentPath.equals(path)) {
      history.addElement(currentPath);
    }
    currentPath = path;
    status = "Loading...";
    loading = true;
    final int id = ++requestId;
    repaint();
    new Thread(new Runnable() {
      public void run() {
        Page result = ApiClient.fetchPage(Config.getServerUrl(), path);
        final Page p = result;
        display.callSerially(new Runnable() {
          public void run() {
            applyResult(id, p);
          }
        });
      }
    }).start();
  }

  private void applyResult(int id, Page p) {
    if (id != requestId) {
      return; // stale: user already navigated elsewhere
    }
    loading = false;
    if (p.error != null && p.lineCount() == 0) {
      status = "Error: " + p.error;
      if (page == null) {
        showTextPage("Error", p.error);
      }
      repaint();
      return;
    }
    page = p;
    if (p.finalUrl.length() > 0) {
      currentUrl = p.finalUrl;
    }
    buildRows();
    topRow = 0;
    cursorRow = 0;
    status = p.error != null ? "Error: " + p.error : "";
    repaint();
  }

  private void showTextPage(String title, String text) {
    page = new Page();
    page.title = title;
    page.lines.addElement(Line.text(text));
    buildRows();
    topRow = 0;
    cursorRow = 0;
  }

  private void testConnection() {
    status = "Testing...";
    repaint();
    new Thread(new Runnable() {
      public void run() {
        final String res = ApiClient.ping(Config.getServerUrl());
        display.callSerially(new Runnable() {
          public void run() {
            Alert a = new Alert("Server",
                res + "\n" + Config.getServerUrl(),
                null,
                res.indexOf("Connected") == 0 ? AlertType.INFO : AlertType.ERROR);
            a.setTimeout(3000);
            display.setCurrent(a, BrowserScreen.this);
            status = "";
            repaint();
          }
        });
      }
    }).start();
  }

  // --------------------------------------------------------------- layout

  private void buildRows() {
    rows = new Vector();
    if (page == null) {
      return;
    }
    int width = getWidth() - 6;
    if (width < 24) {
      width = 24;
    }
    for (int i = 0; i < page.lineCount(); i++) {
      wrapLine(page.lineAt(i), width);
    }
    if (cursorRow >= rows.size()) {
      cursorRow = 0;
    }
    if (topRow >= rows.size()) {
      topRow = 0;
    }
  }

  private void wrapLine(Line line, int width) {
    String text = line.text;
    if (text == null || text.length() == 0) {
      return;
    }
    int len = text.length();
    int start = 0;
    while (start < len) {
      int w = 0;
      int breakAt = -1;
      int i = start;
      while (i < len) {
        char c = text.charAt(i);
        if (c == ' ') {
          breakAt = i;
        }
        w += font.charWidth(c);
        if (w > width) {
          break;
        }
        i++;
      }
      int end;
      if (i >= len) {
        end = len;
      } else if (breakAt > start) {
        end = breakAt;
      } else {
        end = i;
      }
      rows.addElement(new Row(text.substring(start, end), line.linkIdx, line.url));
      start = end;
      while (start < len && text.charAt(start) == ' ') {
        start++;
      }
    }
  }

  private int visibleRows() {
    int h = getHeight() - barH * 2;
    int n = h / rowH;
    return n < 1 ? 1 : n;
  }

  private void ensureVisible() {
    int vis = visibleRows();
    if (cursorRow < topRow) {
      topRow = cursorRow;
    }
    if (cursorRow >= topRow + vis) {
      topRow = cursorRow - vis + 1;
    }
    clampScroll();
  }

  private void clampScroll() {
    if (topRow > rows.size() - 1) {
      topRow = rows.size() - 1;
    }
    if (topRow < 0) {
      topRow = 0;
    }
  }

  // -------------------------------------------------------------- painting

  protected void paint(Graphics g) {
    int w = getWidth();
    int h = getHeight();
    g.setFont(font);
    g.setColor(COLOR_BG);
    g.fillRect(0, 0, w, h);

    // header
    g.setColor(COLOR_BAR);
    g.fillRect(0, 0, w, barH);
    g.setColor(0xFFFFFF);
    String head = (page != null && page.title.length() > 0) ? page.title : currentUrl;
    if (head == null || head.length() == 0) {
      head = "MIDBrowse";
    }
    g.drawString(clip(head, w - 8), 4, 2, Graphics.TOP | Graphics.LEFT);

    // content
    int y = barH;
    int limit = h - barH;
    if (rows.size() == 0 && !loading) {
      g.setColor(COLOR_MUTED);
      String hint = "Menu: set server, then Open URL / Search";
      g.drawString(clip(hint, w - 8), 4, y + 4, Graphics.TOP | Graphics.LEFT);
    }
    for (int i = topRow; i < rows.size(); i++) {
      if (y + rowH > limit) {
        break;
      }
      Row r = (Row) rows.elementAt(i);
      boolean cursor = (i == cursorRow);
      boolean link = r.isLink();
      if (cursor) {
        g.setColor(link ? COLOR_LINK_CURSOR_BG : COLOR_CURSOR_BG);
        g.fillRect(0, y, w, rowH);
      }
      g.setColor(cursor
          ? (link ? COLOR_LINK_CURSOR_TEXT : COLOR_CURSOR_TEXT)
          : (link ? COLOR_LINK : COLOR_TEXT));
      g.drawString(r.text, 4, y + 1, Graphics.TOP | Graphics.LEFT);
      if (link) {
        int uw = font.stringWidth(r.text);
        if (uw > 0) {
          g.drawLine(4, y + rowH - 1, 4 + uw, y + rowH - 1);
        }
      }
      y += rowH;
    }

    // footer
    g.setColor(COLOR_BAR);
    g.fillRect(0, h - barH, w, barH);
    g.setColor(0xFFFFFF);
    String foot;
    if (status.length() > 0) {
      foot = status;
    } else {
      foot = (cursorRow + 1) + "/" + rows.size() + "  links:" + (page == null ? 0 : page.linkCount());
    }
    g.drawString(clip(foot, w - 8), 4, h - barH + 2, Graphics.TOP | Graphics.LEFT);
  }

  private String clip(String s, int maxW) {
    if (s == null) {
      return "";
    }
    if (font.stringWidth(s) <= maxW) {
      return s;
    }
    int n = s.length();
    while (n > 0 && font.stringWidth(s.substring(0, n)) > maxW) {
      n--;
    }
    return s.substring(0, n);
  }

  // ----------------------------------------------------------------- input

  protected void keyPressed(int keyCode) {
    handleKey(keyCode, true);
  }

  protected void keyRepeated(int keyCode) {
    handleKey(keyCode, false);
  }

  private void handleKey(int keyCode, boolean allowActions) {
    int action = 0;
    try {
      action = getGameAction(keyCode);
    } catch (Exception e) {
      // not a game-action key
    }
    if (action == Canvas.UP) {
      moveCursor(-1);
    } else if (action == Canvas.DOWN) {
      moveCursor(1);
    } else if (action == Canvas.LEFT) {
      scrollPage(-1);
    } else if (action == Canvas.RIGHT) {
      scrollPage(1);
    } else if (action == Canvas.FIRE && allowActions) {
      activateCursor();
    } else {
      char c = (char) keyCode;
      if (allowActions && c >= '0' && c <= '9') {
        jumpToNthLink(c == '0' ? 10 : c - '0');
      }
    }
  }

  private void moveCursor(int delta) {
    if (rows.size() == 0) {
      return;
    }
    cursorRow += delta;
    if (cursorRow < 0) {
      cursorRow = 0;
    }
    if (cursorRow > rows.size() - 1) {
      cursorRow = rows.size() - 1;
    }
    ensureVisible();
    repaint();
  }

  private void scrollPage(int dir) {
    if (rows.size() == 0) {
      return;
    }
    int step = visibleRows() * 3 / 4;
    if (step < 1) {
      step = 1;
    }
    topRow += dir * step;
    clampScroll();
    int vis = visibleRows();
    if (cursorRow < topRow) {
      cursorRow = topRow;
    }
    if (cursorRow > topRow + vis - 1) {
      cursorRow = topRow + vis - 1;
    }
    if (cursorRow > rows.size() - 1) {
      cursorRow = rows.size() - 1;
    }
    if (cursorRow < 0) {
      cursorRow = 0;
    }
    repaint();
  }

  private void activateCursor() {
    if (cursorRow >= rows.size()) {
      return;
    }
    Row r = (Row) rows.elementAt(cursorRow);
    if (r.isLink()) {
      openUrl(r.url);
    }
  }

  // Jump to the nth link currently on screen (digit keys 1-9, 0 = 10th).
  private void jumpToNthLink(int n) {
    int vis = visibleRows();
    int end = topRow + vis;
    if (end > rows.size()) {
      end = rows.size();
    }
    int count = 0;
    int lastIdx = -2;
    for (int i = topRow; i < end; i++) {
      Row r = (Row) rows.elementAt(i);
      if (!r.isLink() || r.linkIdx == lastIdx) {
        continue;
      }
      lastIdx = r.linkIdx;
      count++;
      if (count == n) {
        cursorRow = i;
        repaint();
        return;
      }
    }
  }
}
