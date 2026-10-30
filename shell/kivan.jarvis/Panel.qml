// Jarvis's chat on the laptop (docs/PLAN.md, Phase 4). One layer-shell window shows Chat.qml in
// two places: dropped down under the bar badge (`omarchy-shell jarvis dropdown <x>`, from
// Badge.qml), or centered over a scrim (`omarchy-shell jarvis toggle|open`, SUPER+M, and a click
// on a chat toast). Both take the keyboard, since an xdg popup can't be typed into reliably.
//
// App channels (docs/PLAN.md, "App channels") share the window: a strip above the card switches
// between the chat and each channel (Ctrl+1…9, Ctrl+K), and a channel shows Channel.qml, its app's
// view drawn from blocks (`omarchy-shell jarvis channel <name>`; SUPER+N opens חפיפה). The list
// and views come from `jarvis-link channels --json`, read again on each change notice from the watch.
//
// It stays loaded (keepLoaded) so the IPC target exists from shell start. The messages come from
// `jarvis-link json` once, then from a long-running `jarvis-link watch` (JSON lines); sending runs
// `jarvis-link msg` and `jarvis-link send`, whose own events bring the new rows in.
import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import QtQuick
import qs.Commons
import qs.Ui

Item {
  id: root

  // Injected by omarchy-shell's panel loader.
  property var shell: null
  property var manifest: null

  readonly property string cli: Quickshell.env("HOME") + "/.cargo/bin/jarvis-link"

  // A test run puts the panel on that output and never takes the keyboard.
  readonly property string testOutput: Quickshell.env("JARVIS_PANEL_OUTPUT") || ""

  property bool opened: false
  property string channel: ""               // "" is the chat
  property var channels: []                 // `jarvis-link channels --json`
  property var threadItems: []              // the open thread channel's items
  property var pending: ({})                // "<channel>/<compose id>" → [{uid, text}] not in a view yet;
                                            // a list item's reply: "<channel>/<list id>/<item id>" → [{uid, text, item}]
  property bool switcher: false             // the Ctrl+K list
  readonly property var current: {
    for (var i = 0; i < channels.length; i++) if (channels[i].name === channel) return channels[i]
    return null
  }
  property string mode: "center"            // or "dropdown"
  property int anchorX: -1                  // dropdown: the badge's centre on screen
  property string reopenMode: ""            // set while the file chooser has the panel hidden

  property bool daemonUp: false
  property var status: ({ connected: false, peer: null, queued: 0, unread: 0 })
  readonly property string peerName: status.peer && status.peer.name ? status.peer.name : "Phone"
  readonly property string statusLine: !daemonUp ? "daemon not running"
    : !status.peer ? "not paired"
    : (status.connected ? "connected" : "not connected")
      + (status.queued > 0 ? " · " + status.queued + " waiting to send" : "")
      + (!status.connected && status.wake_unanswered ? " · phone didn't answer" : "")

  ListModel { id: messageModel }
  readonly property alias messages: messageModel

  // --- Host lifecycle (the shell's summon/hide) --------------------------------------------------

  function open(payloadJson) {
    var p = ({})
    try { p = JSON.parse(payloadJson || "{}") } catch (e) {}
    show(p.mode === "dropdown" ? "dropdown" : "center", p.x)
  }

  function close() {
    opened = false
  }

  function show(m, x) {
    mode = m
    anchorX = x === undefined || x === "" ? -1 : Number(x)
    opened = true
    switcher = false
    if (channel === "") markRead()
    reloadChannels()
    Qt.callLater(focusCard)
  }

  function focusCard() {
    if (channel === "") { card.focusInput(); card.scrollToEnd() }
    else chanCard.focusFirst()
  }

  // `name` "" is the chat. An unknown channel opens anyway: its view may be on the way.
  function switchTo(name) {
    channel = name
    switcher = false
    threadItems = []
    if (name === "") markRead()
    else reloadThread()
    Qt.callLater(focusCard)
  }

  function showChannel(name) {
    switchTo(name)
    show("center")
  }

  IpcHandler {
    target: "jarvis"
    function toggle(): void { if (root.opened && root.mode === "center") root.close(); else root.show("center") }
    function open(): void { root.show("center") }
    function close(): void { root.close() }
    function dropdown(x: string): void { if (root.opened) root.close(); else root.show("dropdown", x) }
    function isOpen(): bool { return root.opened }
    function channel(name: string): void { root.showChannel(name) }
    function toggleChannel(name: string): void {
      if (root.opened && root.channel === name) root.close(); else root.showChannel(name)
    }
  }

  Component.onCompleted: reload()

  // --- Data -------------------------------------------------------------------------------------

  function reload() {
    if (!loader.running) loader.running = true
  }

  Process {
    id: loader
    command: [root.cli, "json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: root.applySnapshot(text)
    }
    onExited: function(code) { if (code !== 0) root.daemonUp = false }
  }

  function applySnapshot(text) {
    var snap
    try { snap = JSON.parse(text) } catch (e) { daemonUp = false; return }
    daemonUp = true
    status = snap.status
    messageModel.clear()
    for (var i = 0; i < snap.messages.length; i++) messageModel.append(row(snap.messages[i]))
  }

  // A message as a ListModel row: no nulls, and the day it falls on for the section headers.
  function row(m) {
    return {
      mid: m.id,
      fromMe: m.from_me === true,
      ts: m.ts_ms,
      day: Qt.formatDate(new Date(m.ts_ms), "yyyy-MM-dd"),
      kind: m.kind || "text",
      text: m.text || "",
      fileName: m.file_name || "",
      fileSize: m.file_size || 0,
      path: m.path || "",
      state: m.state || "",
      done: 0,
      total: 0
    }
  }

  function indexOf(id) {
    for (var i = messageModel.count - 1; i >= 0; i--)
      if (messageModel.get(i).mid === id) return i
    return -1
  }

  function upsert(m) {
    var r = row(m)
    var i = indexOf(r.mid)
    if (i < 0) {
      messageModel.append(r)
    } else {
      // Keep the transfer progress; a finished file ends it.
      var old = messageModel.get(i)
      if (r.state !== "incoming" && r.state !== "queued") { r.done = 0; r.total = 0 }
      else { r.done = old.done; r.total = old.total }
      messageModel.set(i, r)
    }
  }

  Process {
    id: watch
    command: [root.cli, "watch"]
    running: true
    stdout: SplitParser { onRead: function(line) { root.onEvent(line) } }
    // Catch up on whatever happened while the watch wasn't running.
    onRunningChanged: if (running) { root.reload(); root.reloadChannels() }
    onExited: { root.daemonUp = false; rewatch.start() }
  }

  Timer { id: rewatch; interval: 5000; onTriggered: watch.running = true }
  Timer { id: statusSoon; interval: 150; onTriggered: if (!statusProc.running) statusProc.running = true }
  // No event marks a wake going unanswered (the daemon decides after 30 s), so poll while waiting.
  Timer {
    interval: 10000; repeat: true
    running: root.opened && root.status.queued > 0 && !root.status.connected
    onTriggered: statusSoon.restart()
  }

  Process {
    id: statusProc
    command: [root.cli, "status", "--json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        try { root.status = JSON.parse(text); root.daemonUp = true } catch (e) {}
      }
    }
  }

  function onEvent(line) {
    var e
    try { e = JSON.parse(line) } catch (err) { return }
    daemonUp = true
    if (e.type === "app") {
      reloadChannels()
      if (e.channel === channel) reloadThread()
      return
    }
    if (e.event === "message") {
      upsert(e)
      if (!e.from_me && opened && (e.state === "received" || e.state === "incoming")) markRead()
      statusSoon.restart()
    } else if (e.event === "progress") {
      var i = indexOf(e.id)
      if (i >= 0) { messageModel.setProperty(i, "done", e.done); messageModel.setProperty(i, "total", e.total) }
    } else if (e.event === "paired" || e.event === "unpaired") {
      reload()
    } else if (e.event === "connected" || e.event === "disconnected" || e.event === "read") {
      statusSoon.restart()
    }
  }

  // --- Channels ---------------------------------------------------------------------------------

  function reloadChannels() { chanSoon.restart() }
  function reloadThread() { if (current && current.kind === "thread") threadSoon.restart() }

  Timer { id: chanSoon; interval: 80; onTriggered: if (!chanProc.running) chanProc.running = true }
  Timer { id: threadSoon; interval: 80; onTriggered: if (!threadProc.running && root.channel) threadProc.running = true }

  Process {
    id: chanProc
    command: [root.cli, "channels", "--json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        var list
        try { list = JSON.parse(text) } catch (e) { return }
        if (!Array.isArray(list)) return
        // `laptop = false` in a manifest keeps a channel on the phone only.
        root.channels = list.filter(function (c) { return c.laptop !== false })
        root.prunePending()
        root.reloadThread()
      }
    }
  }

  Process {
    id: threadProc
    command: [root.cli, "thread", root.channel, "--json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: { try { root.threadItems = JSON.parse(text) } catch (e) {} }
    }
  }

  function act(name, obj) {
    Quickshell.execDetached([cli, "action", name, JSON.stringify(obj)])
  }

  function pendingFor(name, id) { return pending[name + "/" + id] || [] }

  function addPending(name, id, uid, text, item) {
    var p = Object.assign({}, pending), k = name + "/" + id   // a new object, so bindings notice
    p[k] = (p[k] || []).concat([{ uid: uid, text: text, item: item }])
    pending = p
  }

  // An echo goes once a view lists an item with its uid; a reply's also once the view no longer
  // lists its item (the question was answered and dropped).
  function prunePending() {
    var p = ({}), changed = false
    for (var k in pending) {
      var name = k.slice(0, k.indexOf("/")), ids = ({}), seen = false
      for (var i = 0; i < channels.length; i++) {
        if (channels[i].name !== name || !channels[i].view || !channels[i].view.blocks) continue
        seen = true
        var bs = channels[i].view.blocks
        for (var j = 0; j < bs.length; j++)
          for (var n = 0; bs[j].items && n < bs[j].items.length; n++) ids[bs[j].items[n].id] = true
      }
      var left = pending[k].filter(function(e) { return !ids[e.uid] && !(e.item && seen && !ids[e.item]) })
      if (left.length !== pending[k].length) changed = true
      if (left.length) p[k] = left
    }
    if (changed) pending = p
  }

  // --- Actions ----------------------------------------------------------------------------------

  function markRead() {
    if (daemonUp) Quickshell.execDetached([cli, "read"])
  }

  function sendText(t) {
    Quickshell.execDetached([cli, "msg", "--", t])
  }

  // Files first, then the text, in that order, so a caption lands under its image.
  function sendWith(paths, t) {
    Quickshell.execDetached(["sh", "-c",
      'cli="$1"; t="$2"; shift 2; "$cli" send -- "$@" >/dev/null && { [ -z "$t" ] || "$cli" msg -- "$t"; }',
      "sh", cli, t].concat(paths))
  }

  function cancel(id) {
    Quickshell.execDetached([cli, "cancel", id])
  }

  function ring() {
    Quickshell.execDetached([cli, "ring"])
  }

  // As image data, the way received images land on the clipboard.
  function copyImage(path) {
    if (path) Quickshell.execDetached([cli, "copy", path])
  }

  function copy(t) {
    if (t) Quickshell.execDetached(["wl-copy", "--", t])
  }

  function openFile(path) {
    if (!path) return
    close()
    Quickshell.execDetached(["xdg-open", path])
  }

  // The file chooser is an ordinary window under this overlay, and the overlay holds the
  // keyboard, so the panel steps aside until the chooser closes.
  function pickFiles() {
    if (picker.running) return
    reopenMode = mode
    close()
    picker.running = true
  }

  Process {
    id: picker
    command: [root.cli, "send", "--pick"]
    onExited: {
      var m = root.reopenMode
      root.reopenMode = ""
      if (m) root.show(m, m === "dropdown" ? root.anchorX : undefined)
    }
  }

  // Ctrl+V: an image on the clipboard is saved and attached, to go with the next Enter; anything
  // else is pasted as text (exit 7).
  function paste() {
    if (!paster.running) paster.running = true
  }

  Process {
    id: paster
    command: ["sh", "-c", "wl-paste --list-types 2>/dev/null | grep -q '^image/' || exit 7; exec \"$0\" paste", root.cli]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: { var p = text.trim(); if (p) card.attach(p) }
    }
    onExited: function(code) { if (code === 7) card.pasteText() }
  }

  // --- Surface ----------------------------------------------------------------------------------

  PanelWindow {
    id: panel
    visible: root.opened
    anchors { top: true; bottom: true; left: true; right: true }
    color: "transparent"
    WlrLayershell.namespace: "omarchy-jarvis"
    WlrLayershell.layer: WlrLayer.Overlay
    WlrLayershell.keyboardFocus: !root.opened ? WlrKeyboardFocus.None
      // A test run must never take the keyboard: Hyprland gives even OnDemand layers focus on map.
      : root.testOutput ? WlrKeyboardFocus.None : WlrKeyboardFocus.Exclusive
    Binding on screen {
      when: root.testOutput !== ""
      value: Quickshell.screens.find(function(s) { return s.name === root.testOutput }) || null
    }
    // The dropdown keeps clear of the bar, so its top edge is just under it; the centered
    // panel covers the whole screen with its scrim.
    exclusionMode: root.mode === "dropdown" ? ExclusionMode.Normal : ExclusionMode.Ignore

    readonly property bool dropdown: root.mode === "dropdown"
    readonly property int gap: Style.gapsOut

    Rectangle {
      anchors.fill: parent
      color: panel.dropdown ? "transparent" : Color.menu.scrim
    }

    MouseArea { anchors.fill: parent; onClicked: root.close() }

    // The strip (only with channels) sits above the card and moves it down by its height.
    readonly property int stripH: root.channels.length > 0 ? Style.space(28) + Style.space(4) : 0
    readonly property int cardW: Math.min(panel.dropdown ? 330 : 440, panel.width - panel.gap * 2)
    readonly property int cardH: Math.min(panel.dropdown ? 400 : 470, panel.height - panel.gap * 2 - stripH)
    readonly property int cardX: panel.dropdown && root.anchorX >= 0
      ? Math.max(panel.gap, Math.min(root.anchorX - cardW / 2, panel.width - cardW - panel.gap))
      : Math.round((panel.width - cardW) / 2)
    readonly property int cardY: (panel.dropdown ? panel.gap : Math.round((panel.height - cardH - stripH) / 2)) + stripH

    Strip {
      id: strip
      visible: root.channels.length > 0
      x: panel.cardX
      y: panel.cardY - panel.stripH
      width: panel.cardW
      height: Style.space(28)
    }

    Chat {
      id: card
      visible: root.channel === ""
      ui: root
      roomy: !panel.dropdown
      width: panel.cardW
      height: panel.cardH
      x: panel.cardX
      y: panel.cardY

      MouseArea { anchors.fill: parent; z: -1; onClicked: card.focusInput() }
    }

    Channel {
      id: chanCard
      visible: root.channel !== ""
      ui: root
      channel: root.current || (root.channel ? { name: root.channel, title: root.channel, glyph: root.channel.charAt(0).toUpperCase(), dir: "auto", kind: "app", view: null } : null)
      roomy: !panel.dropdown
      width: panel.cardW
      height: panel.cardH
      x: panel.cardX
      y: panel.cardY

      MouseArea { anchors.fill: parent; z: -1; onClicked: chanCard.focusFirst() }
    }

    Switcher {
      visible: root.switcher
      x: panel.cardX + Math.round((panel.cardW - width) / 2)
      y: panel.cardY + Style.space(40)
      width: Math.min(260, panel.cardW - Style.space(20))
    }

    // Ctrl+1 is the chat, Ctrl+2… the channels in the strip's order; Ctrl+K picks by name.
    Shortcut { sequence: "Ctrl+K"; enabled: root.opened; onActivated: root.switcher = !root.switcher }
    Repeater {
      model: 9
      Item {
        required property int index
        Shortcut {
          sequence: "Ctrl+" + (index + 1)
          enabled: root.opened && index <= root.channels.length
          onActivated: root.switchTo(index === 0 ? "" : root.channels[index - 1].name)
        }
      }
    }
  }

  // The tabs above the card: the chat, then each channel with its glyph and badge.
  component Strip: Row {
    spacing: Style.space(4)
    Tab { name: ""; glyph: "󰭹"; title: "Chat"; badge: root.status.unread || 0; tint: Color.accent }
    Repeater {
      model: root.channels
      Tab {
        required property var modelData
        name: modelData.name
        glyph: modelData.glyph
        mark: modelData.icon || null
        title: modelData.title
        badge: modelData.badge || 0
        tint: modelData.accent || Color.accent
      }
    }
  }

  component Tab: Rectangle {
    id: tab
    property string name: ""
    property string glyph: ""
    property var mark: null
    property string title: ""
    property int badge: 0
    property color tint: Color.accent
    readonly property bool on: root.channel === name
    width: tabRow.implicitWidth + Style.space(16)
    height: Style.space(28)
    radius: Math.max(4, Style.cornerRadius)
    color: on ? Color.popups.background : Util.alpha(Color.popups.background, tabHover.hovered ? 0.85 : 0.6)
    border.color: on ? tint : Util.alpha(Color.popups.text, 0.15)
    Row {
      id: tabRow
      anchors.centerIn: parent
      spacing: Style.space(5)
      Mark { visible: !!tab.mark; mark: tab.mark; color: tab.on ? tab.tint : Color.popups.text; width: Style.font.bodySmall + 2; height: width; anchors.verticalCenter: parent.verticalCenter }
      Text { visible: !tab.mark; text: tab.glyph; color: tab.on ? tab.tint : Color.popups.text; font.family: Style.font.family; font.pixelSize: Style.font.bodySmall; anchors.verticalCenter: parent.verticalCenter }
      Text { text: tab.title; color: Color.popups.text; font.family: Style.font.family; font.pixelSize: Style.font.caption; font.bold: tab.on; anchors.verticalCenter: parent.verticalCenter }
      Rectangle {
        visible: tab.badge > 0
        anchors.verticalCenter: parent.verticalCenter
        width: Math.max(height, badgeText.implicitWidth + Style.space(8)); height: Style.space(16); radius: height / 2
        color: tab.tint
        Text { id: badgeText; anchors.centerIn: parent; text: tab.badge; color: Color.popups.background; font.pixelSize: Style.font.caption; font.bold: true }
      }
    }
    HoverHandler { id: tabHover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.switchTo(tab.name) }
  }

  // Ctrl+K: type to filter, Enter opens the first match, Esc goes back.
  component Switcher: Rectangle {
    id: sw
    readonly property var all: [{ name: "", title: "Chat", glyph: "󰭹" }].concat(root.channels)
    readonly property var shown: all.filter(function(c) {
      var q = filter.text.trim().toLowerCase()
      return !q || c.title.toLowerCase().indexOf(q) >= 0 || c.name.indexOf(q) >= 0
    })
    height: swCol.implicitHeight + Style.space(12)
    radius: Math.max(4, Style.cornerRadius)
    color: Color.popups.background
    border.color: Color.accent
    z: 20
    onVisibleChanged: if (visible) { filter.text = ""; filter.forceActiveFocus() } else Qt.callLater(root.focusCard)

    Column {
      id: swCol
      anchors { left: parent.left; right: parent.right; top: parent.top; margins: Style.space(6) }
      spacing: Style.space(3)
      TextInput {
        id: filter
        width: parent.width
        color: Color.popups.text
        font.family: Style.font.family
        font.pixelSize: Style.font.bodySmall
        Keys.onPressed: function(event) {
          if (event.key === Qt.Key_Escape) { event.accepted = true; root.switcher = false }
          else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
            event.accepted = true
            if (sw.shown.length) root.switchTo(sw.shown[0].name)
          }
        }
        Text { visible: !filter.text; text: "Go to channel…"; color: Util.alpha(Color.popups.text, 0.45); font: filter.font }
      }
      Repeater {
        model: sw.shown
        Rectangle {
          required property var modelData
          required property int index
          width: swCol.width
          height: Style.space(24)
          radius: 4
          color: index === 0 ? Util.alpha(Color.accent, 0.25) : (swHover.hovered ? Util.alpha(Color.popups.text, 0.08) : "transparent")
          Mark {
            id: swMark
            visible: !!modelData.icon
            mark: modelData.icon || null
            color: Color.popups.text
            width: visible ? Style.font.bodySmall : 0; height: Style.font.bodySmall
            anchors { left: parent.left; leftMargin: Style.space(6); verticalCenter: parent.verticalCenter }
          }
          Text {
            anchors { left: swMark.right; leftMargin: modelData.icon ? Style.space(6) : 0; verticalCenter: parent.verticalCenter }
            text: modelData.icon ? modelData.title : modelData.glyph + "  " + modelData.title
            color: Color.popups.text
            font.family: Style.font.family
            font.pixelSize: Style.font.bodySmall
          }
          HoverHandler { id: swHover; cursorShape: Qt.PointingHandCursor }
          TapHandler { onTapped: root.switchTo(modelData.name) }
        }
      }
    }
  }
}
