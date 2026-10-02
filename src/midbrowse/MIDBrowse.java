package midbrowse;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

import midbrowse.config.Config;
import midbrowse.ui.BrowserScreen;

// Main MIDlet: a tiny web browser. All real fetching happens on the desktop
// backend (server/server.js); this app only requests pages from it and renders
// the simplified line protocol on a canvas.
public class MIDBrowse extends MIDlet {

  private BrowserScreen screen;

  public void startApp() {
    if (screen == null) {
      Config.load();
      screen = new BrowserScreen(this);
      Display.getDisplay(this).setCurrent(screen);
      screen.begin();
    }
  }

  public void pauseApp() {
  }

  public void destroyApp(boolean unconditional) {
    if (screen != null) {
      screen.shutdown();
    }
  }

  public BrowserScreen getScreen() {
    return screen;
  }
}
