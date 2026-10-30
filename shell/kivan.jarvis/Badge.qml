// The chat badge in the bar: a chat glyph, plus the unread count in the urgent colour while the
// phone has sent something unread. A click drops the chat down under it (Panel.qml, through the
// `jarvis-link` IPC target, so one window serves the dropdown and the SUPER+M panel).
//
// It keeps its own `jarvis-link watch` and asks `jarvis-link status --json` again after each event; the
// daemon sends a `read` event when the chat marks messages read, so the count clears with it.
import QtQuick
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui

BarWidget {
  id: root
  moduleName: "kivan.jarvis"

  readonly property string cli: Quickshell.env("HOME") + "/.cargo/bin/jarvis-link"

  property int unread: 0
  property bool connected: false
  property bool paired: true
  property bool daemonUp: false

  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight

  function refresh() {
    if (!statusProc.running) statusProc.running = true
  }

  Process {
    id: statusProc
    command: [root.cli, "status", "--json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        var s
        try { s = JSON.parse(text) } catch (e) { root.daemonUp = false; return }
        root.daemonUp = true
        root.unread = s.unread || 0
        root.connected = s.connected === true
        root.paired = !!s.peer
      }
    }
    onExited: function(code) { if (code !== 0) root.daemonUp = false }
  }

  // Any event can change the count or the link; a burst of them costs one status call.
  Process {
    id: watch
    command: [root.cli, "watch"]
    running: true
    stdout: SplitParser { onRead: refreshSoon.restart() }
    onRunningChanged: if (running) root.refresh()
    // The daemon restarted or isn't up yet: try again shortly.
    onExited: { root.daemonUp = false; rewatch.start() }
  }

  Timer { id: rewatch; interval: 5000; onTriggered: watch.running = true }
  Timer { id: refreshSoon; interval: 150; onTriggered: root.refresh() }

  WidgetButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.unread > 0 ? "󰍡 " + root.unread : "󰍡"
    active: root.unread > 0
    dimmed: !root.daemonUp || !root.paired
    horizontalMargin: 6
    tooltipText: !root.daemonUp ? "Jarvis isn't running"
      : !root.paired ? "Jarvis: not paired"
      : (root.unread > 0 ? root.unread + " unread · " : "") + (root.connected ? "Phone connected" : "Phone not connected")

    onPressed: function(b) {
      // The badge's centre in the bar window, which spans the screen, so the dropdown opens
      // under it.
      var p = button.mapToItem(null, button.width / 2, 0)
      Quickshell.execDetached(["omarchy-shell", "-q", "jarvis", "dropdown", String(Math.round(p.x))])
    }
  }
}
