// An app channel's card (docs/PLAN.md, "App channels"): the view its app published, drawn from
// the blocks, and the actions sent back through `jarvis-link action`. A channel without an app (kind
// `thread`) shows its posts and takes a reply; any other kind (`app`, `list`) shows its view. `ui`
// is Panel.qml; `channel` is one entry of `jarvis-link channels --json`.
//
// Direction: `rtl` mirrors the whole card, `ltr` doesn't; `auto` (the default) mirrors nothing but
// each list/checklist row by its own text, and aligns each text and box by its own content.
//
// The blocks are rebuilt whenever a new view arrives, so what is being typed lives in `drafts`
// (by block and field id), open list details in `expanded`, and the focused box gets its focus back.
import QtQuick
import QtQuick.Controls as QQC
import qs.Commons
import qs.Ui

BorderSurface {
  id: ch

  property var ui: null
  property var channel: null
  property bool roomy: false

  readonly property string name: channel ? channel.name : ""
  readonly property bool rtl: !!channel && channel.dir === "rtl"
  readonly property bool autoDir: !!channel && channel.dir !== "rtl" && channel.dir !== "ltr"
  readonly property bool thread: !!channel && channel.kind === "thread"
  readonly property color fg: Color.popups.text
  readonly property color muted: Util.alpha(fg, 0.55)
  readonly property color accent: channel && channel.accent ? channel.accent : Color.accent
  readonly property string fontFamily: Style.font.family
  readonly property int radius2: Math.max(4, Style.cornerRadius)
  readonly property int pad: roomy ? Style.spacing.xl : Style.spacing.lg
  readonly property int gap: Style.space(8)

  // The view's blocks; a thread's posts become a list and a reply box.
  readonly property var blocks: {
    if (!channel) return []
    if (thread) return threadBlocks(ui ? ui.threadItems : [])
    var v = channel.view
    return v && Array.isArray(v.blocks) ? v.blocks : []
  }
  // A view with a thread (dibs's conversation) opens at its end and follows new lines.
  readonly property bool hasThread: {
    for (var i = 0; i < blocks.length; i++) if (blocks[i] && blocks[i].type === "thread") return true
    return false
  }
  // The header block goes in the card's header, not the body.
  readonly property var headerBlock: {
    for (var i = 0; i < blocks.length; i++) if (blocks[i] && blocks[i].type === "header") return blocks[i]
    return null
  }

  property var drafts: ({})        // "<block>/<field>" → text; "<block>/chips" → [ids]
  property string focusKey: ""     // the box that had focus, to give it back after a rebuild
  property string armed: ""        // an action waiting for its confirming second press
  property var expanded: ({})      // "<channel>/<item id>" → true: that list item's details are open
  property var dismissed: ({})     // "<list id>/<item id>" → true: removed with its ✕, until a view drops it

  LayoutMirroring.enabled: rtl
  LayoutMirroring.childrenInherit: true

  color: Color.popups.background
  borderSpec: Border.localOrSurfaceSpec("popups", "border", Color.popups.border, Color.popups.border, Math.max(1, Style.space(2)))
  radius: Style.cornerRadius

  onNameChanged: { drafts = ({}); focusKey = ""; armed = ""; dismissed = ({}); flick.follow = true }
  // A list item's `dismiss`: hidden at once; its id goes to the app like an item action's tap.
  function dismiss(block, item, action) {
    var d = Object.assign({}, dismissed); d[block + "/" + item] = true; dismissed = d
    send({ action: action, value: { item: item } })
  }
  // Forget the dismissed items a new view no longer lists (the app took them).
  onBlocksChanged: {
    var listed = ({}), d = ({}), any = false
    for (var i = 0; i < blocks.length; i++) {
      var its = blocks[i] && blocks[i].items
      if (Array.isArray(its)) for (var j = 0; j < its.length; j++) if (its[j]) listed[blocks[i].id + "/" + its[j].id] = true
    }
    for (var k in dismissed) { if (listed[k]) d[k] = true; else any = true }
    if (any) dismissed = d
  }

  function draft(key, fallback) { return drafts[key] !== undefined ? drafts[key] : (fallback || "") }
  // A new object each time: reassigning the same one doesn't notify the bindings on it.
  function setDraft(key, value) { var d = Object.assign({}, drafts); d[key] = value; drafts = d }
  function toggleExpanded(key) { var e = Object.assign({}, expanded); e[key] = !e[key]; expanded = e }
  function clearDrafts(prefix) {
    var d = ({})
    for (var k in drafts) if (k.indexOf(prefix + "/") !== 0) d[k] = drafts[k]
    drafts = d
  }

  function uid() {
    var s = ""
    for (var i = 0; i < 4; i++) s += Math.floor(Math.random() * 0x10000).toString(16).padStart(4, "0")
    return Date.now().toString(16) + "-" + s
  }

  function send(obj) { if (ui) ui.act(name, obj) }

  // A second press within a few seconds confirms.
  function press(key, confirm, obj) {
    if (confirm && armed !== key) { armed = key; disarm.restart(); return }
    armed = ""
    send(obj)
  }
  Timer { id: disarm; interval: 4000; onTriggered: ch.armed = "" }
  // An armed button shows its `confirm` text (when it is one, not just `true`).
  function pressLabel(key, a) {
    return armed === key && typeof a.confirm === "string" && a.confirm !== "" ? a.confirm : a.label
  }

  // True when the first strong character is Hebrew or Arabic; a Latin letter first, or none, is ltr.
  function rtlOf(text) {
    var t = text === undefined || text === null ? "" : String(text)
    for (var i = 0; i < t.length; i++) {
      var c = t.charCodeAt(i)
      if ((c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return true
      if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || (c >= 0xC0 && c <= 0x24F && c !== 0xD7 && c !== 0xF7)) return false
    }
    return false
  }
  // In auto a text goes to the side its own content starts from; `flipped` = it sits in a row that
  // auto mirrored, which mirrors the alignment too. In ltr and rtl it stays AlignLeft (the card's
  // mirroring turns it right in rtl), as before.
  function alignOf(text, flipped) {
    if (!autoDir) return Text.AlignLeft
    return rtlOf(text) !== !!flipped ? Text.AlignRight : Text.AlignLeft
  }

  // The text box being typed in stays in view: scrolled to when it gets focus, and again when
  // the blocks above it grow (a long list pushes the compose below the card).
  property Item focusedBox: null
  function reveal(item) {
    if (!item || !item.visible) return
    var top = item.mapToItem(body, 0, 0).y + body.y
    var bottom = top + item.height + ch.pad
    if (top < flick.contentY) flick.contentY = top
    else if (bottom > flick.contentY + flick.height)
      flick.contentY = Math.max(0, Math.min(bottom - flick.height, flick.contentHeight - flick.height))
  }

  function focusFirst() {
    var box = body.findBox(focusKey) || body.findBox("")
    if (box) box.forceActiveFocus()
  }

  function threadBlocks(items) {
    var list = []
    for (var i = 0; i < items.length; i++) {
      var it = items[i], d = it.data || {}
      var p = d.post || d
      var text = typeof d === "string" ? d : (p.text || (d.action ? "↳ " + d.action : ""))
      list.push({
        id: it.id, title: p.title || "", text: text,
        meta: (it.from_me ? "" : "↩ ") + Qt.formatDateTime(new Date(it.ts_ms), "d/M HH:mm"),
        buttons: p.actions || []
      })
    }
    return [
      { type: "list", id: "_thread", items: list, empty: "No posts yet." },
      { type: "compose", id: "_reply", placeholder: "Reply…", submit: "Send" }
    ]
  }

  // A thread line's day ("2026-10-05") and its header ("Today", "Yesterday", "Sat 3 Oct").
  function dayOf(ts) { return Qt.formatDate(new Date(ts * 1000), "yyyy-MM-dd") }
  function dayLabel(ts) {
    var d = new Date(ts * 1000), now = new Date()
    if (dayOf(ts) === Qt.formatDate(now, "yyyy-MM-dd")) return "Today"
    if (dayOf(ts) === Qt.formatDate(new Date(now.getTime() - 86400000), "yyyy-MM-dd")) return "Yesterday"
    return Qt.formatDate(d, d.getFullYear() === now.getFullYear() ? "ddd d MMM" : "ddd d MMM yyyy")
  }

  function tone(t) {
    return t === "error" ? "#e5534b" : t === "warn" ? "#d29922" : t === "ok" ? "#4cc38a" : ch.accent
  }

  // --- Header -----------------------------------------------------------------------------------

  Item {
    id: header
    anchors { top: parent.top; left: parent.left; right: parent.right }
    anchors.topMargin: ch.contentTopInset
    anchors.leftMargin: ch.contentLeftInset + ch.pad
    anchors.rightMargin: ch.contentRightInset + ch.pad / 2
    height: Style.space(40)

    Rectangle {
      id: glyph
      width: Style.space(26); height: width; radius: width / 2
      // Anchored, not placed by x, so an rtl channel mirrors it to the right.
      anchors { left: parent.left; verticalCenter: parent.verticalCenter }
      // A design-language channel (hue + icon) draws its white mark on its tile, like the phone.
      color: ch.channel && ch.channel.tile ? ch.channel.tile : Util.alpha(ch.accent, 0.25)
      Mark {
        visible: !!(ch.channel && ch.channel.icon)
        mark: ch.channel ? ch.channel.icon || null : null
        color: ch.channel && ch.channel.tile ? "white" : ch.fg
        anchors.centerIn: parent
        width: parent.width * 0.55; height: width
      }
      Text {
        visible: !(ch.channel && ch.channel.icon)
        anchors.centerIn: parent
        text: ch.channel ? ch.channel.glyph : ""
        color: ch.fg
        font.family: ch.fontFamily
        font.pixelSize: Style.font.body
        font.bold: true
      }
    }

    Column {
      anchors { left: glyph.right; leftMargin: Style.space(8); right: closeBtn.left; rightMargin: Style.space(6) }
      anchors.verticalCenter: parent.verticalCenter
      Text {
        width: parent.width
        text: ch.headerBlock && ch.headerBlock.title ? ch.headerBlock.title : (ch.channel ? ch.channel.title : "")
        color: ch.fg
        wrapMode: Text.Wrap
        horizontalAlignment: ch.alignOf(text)
        font.family: ch.fontFamily
        font.pixelSize: Style.font.subtitle
        font.bold: true
      }
      Text {
        width: parent.width
        visible: text !== ""
        text: ch.headerBlock && ch.headerBlock.subtitle ? ch.headerBlock.subtitle
          : (ch.ui && !ch.ui.daemonUp ? "daemon not running" : "")
        color: ch.muted
        wrapMode: Text.Wrap
        horizontalAlignment: ch.alignOf(text)
        font.family: ch.fontFamily
        font.pixelSize: Style.font.caption
      }
    }

    IconBtn {
      id: closeBtn
      anchors { right: parent.right; verticalCenter: parent.verticalCenter }
      glyph: "󰅖"
      onClicked: ui.close()
    }
  }

  Rectangle {
    id: headerLine
    anchors { top: header.bottom; left: parent.left; right: parent.right }
    anchors.leftMargin: ch.contentLeftInset
    anchors.rightMargin: ch.contentRightInset
    height: 1
    color: Util.alpha(ch.fg, 0.1)
  }

  // --- Body -------------------------------------------------------------------------------------

  Flickable {
    id: flick
    anchors { top: headerLine.bottom; left: parent.left; right: parent.right; bottom: parent.bottom }
    anchors.leftMargin: ch.contentLeftInset + ch.pad
    anchors.rightMargin: ch.contentRightInset + ch.pad
    anchors.bottomMargin: ch.contentBottomInset
    clip: true
    contentHeight: body.height + ch.pad * 2
    boundsBehavior: Flickable.StopAtBounds
    QQC.ScrollBar.vertical: QQC.ScrollBar { policy: QQC.ScrollBar.AsNeeded; width: Style.space(5) }
    // With a thread: kept at the end (the newest line and the box) unless scrolled up.
    property bool follow: true
    onMovementEnded: follow = atYEnd
    onContentHeightChanged: if (ch.hasThread && follow) Qt.callLater(function() { flick.contentY = Math.max(0, flick.contentHeight - flick.height) })

    Column {
      id: body
      y: ch.pad
      width: flick.width
      spacing: ch.gap
      onHeightChanged: if (ch.focusedBox && ch.focusedBox.input.activeFocus) Qt.callLater(ch.reveal, ch.focusedBox)

      // The text box for `key` ("" = the first one), searched through the loaded blocks.
      function findBox(key) {
        for (var i = 0; i < rep.count; i++) {
          var l = rep.itemAt(i)
          var b = l && l.item && l.item.box ? l.item.box(key) : null
          if (b) return b
        }
        return null
      }

      Repeater {
        id: rep
        model: ch.blocks
        onItemAdded: Qt.callLater(function() { if (ch.focusKey) ch.focusFirst() })

        Loader {
          required property var modelData
          required property int index
          width: body.width
          active: modelData.type !== "header"
          visible: active && status === Loader.Ready
          sourceComponent: {
            switch (modelData.type) {
              case "notice": return noticeC
              case "text": return textC
              case "list": return listC
              case "thread": return threadC
              case "checklist": return checklistC
              case "compose": return composeC
              case "form": return formC
              case "progress": return progressC
              case "buttons": return buttonsC
              default: return null          // unknown types are skipped
            }
          }
          onLoaded: item.b = modelData
        }
      }

      Text {
        visible: ch.blocks.length === 0
        width: parent.width
        text: ch.ui && !ch.ui.daemonUp ? "The Jarvis daemon isn't running."
          : "Nothing here yet: the app hasn't published a view."
        color: ch.muted
        wrapMode: Text.Wrap
        horizontalAlignment: Text.AlignHCenter
        font.family: ch.fontFamily
        font.pixelSize: Style.font.bodySmall
      }
    }
  }

  // --- Blocks -----------------------------------------------------------------------------------

  Component {
    id: noticeC
    Rectangle {
      property var b: ({})
      function box() { return null }
      height: nt.implicitHeight + Style.space(14)
      radius: ch.radius2
      color: Util.alpha(ch.tone(b.tone), 0.16)
      border.color: Util.alpha(ch.tone(b.tone), 0.45)
      Label {
        id: nt
        anchors { left: parent.left; right: parent.right; verticalCenter: parent.verticalCenter; margins: Style.space(9) }
        text: b.text || ""
      }
    }
  }

  Component {
    id: textC
    Rectangle {
      property var b: ({})
      function box() { return null }
      height: te.implicitHeight + Style.space(16)
      radius: ch.radius2
      color: Util.alpha(ch.fg, 0.05)
      TextEdit {
        id: te
        anchors { left: parent.left; right: parent.right; top: parent.top; margins: Style.space(8) }
        text: b.text || ""
        readOnly: true
        selectByMouse: true
        wrapMode: TextEdit.Wrap
        color: ch.fg
        selectionColor: Style.selectionFillFor(ch.fg, ch.accent)
        selectedTextColor: ch.fg
        horizontalAlignment: ch.alignOf(text)
        font.family: b.mono ? "monospace" : ch.fontFamily
        font.pixelSize: Style.font.bodySmall
      }
      IconBtn {
        anchors { top: parent.top; right: parent.right; margins: Style.space(2) }
        glyph: "󰆏"
        small: true
        onClicked: ui.copy(b.text || "")
      }
    }
  }

  Component {
    id: listC
    Column {
      id: lst
      property var b: ({})
      property var boxes: ({})        // the items' reply boxes by key, to give focus back
      function box(key) { return key !== "" && boxes[key] ? boxes[key] : null }
      spacing: Style.space(5)
      Label {
        visible: !(b.items && b.items.length)
        text: b.empty || ""
        color: ch.muted
      }
      Repeater {
        model: b.items || []
        Rectangle {
          id: row
          required property var modelData
          readonly property var it: modelData
          // In auto the row (meta, chips, buttons) runs the way its own text does.
          readonly property bool flip: ch.autoDir && ch.rtlOf(it.text || it.title || "")
          readonly property string openKey: ch.name + "/" + it.id
          // `reply`: `{"action":<reply id>,"uid","value":{"item","text"}}`; the text waits under the
          // item (⏳) until a view lists the uid or no longer lists the item.
          function sendReply() {
            var t = replyBox.input.text.trim()
            if (!t || !it.reply) return
            var u = ch.uid()
            ch.send({ action: it.reply.id, uid: u, value: { item: it.id, text: t } })
            if (ch.ui) ch.ui.addPending(ch.name, b.id + "/" + it.id, u, t, it.id)
            ch.clearDrafts(b.id + "/" + it.id)
            replyBox.input.text = ""
            ch.focusKey = replyBox.key
          }
          LayoutMirroring.enabled: ch.autoDir ? flip : ch.rtl
          LayoutMirroring.childrenInherit: true
          visible: !ch.dismissed[b.id + "/" + it.id]
          width: parent ? parent.width : 0
          height: rowCol.implicitHeight + Style.space(12)
          radius: ch.radius2
          color: Util.alpha(ch.fg, 0.06)
          // `dismiss`: a small ✕ at the end of the row removes the item.
          IconBtn {
            visible: !!(row.it.dismiss && row.it.dismiss.id)
            anchors { top: parent.top; right: parent.right; margins: Style.space(3) }
            glyph: "󰅖"
            small: true
            onClicked: ch.dismiss(b.id, row.it.id, row.it.dismiss.id)
          }
          Column {
            id: rowCol
            anchors { left: parent.left; right: parent.right; verticalCenter: parent.verticalCenter; margins: Style.space(8) }
            anchors.rightMargin: row.it.dismiss ? Style.space(28) : Style.space(8)
            spacing: Style.space(4)
            Row {
              width: parent.width
              spacing: Style.space(5)
              visible: !!(row.it.meta || row.it.title || (row.it.chips && row.it.chips.length))
              Label { visible: !!row.it.meta; text: row.it.meta || ""; color: ch.muted; small: true; width: implicitWidth }
              Label { visible: !!row.it.title; text: row.it.title || ""; bold: true; width: implicitWidth }
              Repeater {
                model: row.it.chips || []
                Chip { required property var modelData; label: modelData; on: true }
              }
            }
            Label { width: parent.width; text: row.it.text || ""; flipped: row.flip }
            // `details`: shut behind a small toggle; open or shut survives view reloads.
            Row {
              width: parent.width       // so the toggle sits at the start side of a mirrored row
              visible: !!row.it.details
              Label {
                width: implicitWidth
                text: ch.expanded[row.openKey] ? "Details ▴" : "Details ▾"
                color: ch.accent
                small: true
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: ch.toggleExpanded(row.openKey) }
              }
            }
            TextEdit {
              visible: !!row.it.details && !!ch.expanded[row.openKey]
              width: parent.width
              text: row.it.details || ""
              readOnly: true
              selectByMouse: true
              wrapMode: TextEdit.Wrap
              color: ch.muted
              selectionColor: Style.selectionFillFor(ch.fg, ch.accent)
              selectedTextColor: ch.fg
              horizontalAlignment: ch.alignOf(text, row.flip)
              font.family: ch.fontFamily
              font.pixelSize: Style.font.caption
            }
            Row {
              width: parent.width       // a full-width Row starts at the right in rtl
              spacing: Style.space(5)
              visible: !!((row.it.actions && row.it.actions.length) || (row.it.buttons && row.it.buttons.length))
              Repeater {
                model: row.it.actions || []
                Btn {
                  required property var modelData
                  readonly property string key: b.id + "/" + row.it.id + "/" + modelData.id
                  label: ch.pressLabel(key, modelData)
                  style: ch.armed === key ? "danger" : "plain"
                  small: true
                  onClicked: ch.press(key, modelData.confirm, { action: modelData.id, value: { item: row.it.id } })
                }
              }
              // A thread post's buttons: the tap goes back as that action.
              Repeater {
                model: row.it.buttons || []
                Btn {
                  required property var modelData
                  label: modelData.label
                  small: true
                  onClicked: ch.send({ action: modelData.id, value: { item: row.it.id } })
                }
              }
            }
            Repeater {
              model: row.it.reply && ch.ui ? ch.ui.pendingFor(ch.name, b.id + "/" + row.it.id) : []
              Label { required property var modelData; text: "⏳ " + modelData.text; color: ch.muted; flipped: row.flip }
            }
            Row {
              width: parent.width
              spacing: Style.space(6)
              visible: !!row.it.reply
              Box {
                id: replyBox
                width: parent.width - replyBtn.width - parent.spacing
                key: b.id + "/" + row.it.id + "/reply"
                placeholder: row.it.reply && row.it.reply.placeholder ? row.it.reply.placeholder : "Answer…"
                onEnter: row.sendReply()
                Component.onCompleted: { var m = lst.boxes; m[key] = input; lst.boxes = m }
              }
              Btn {
                id: replyBtn
                anchors.bottom: parent.bottom
                label: row.it.reply && row.it.reply.submit ? row.it.reply.submit : "Send"
                style: "primary"
                small: true
                onClicked: row.sendReply()
              }
            }
          }
        }
      }
    }
  }

  // `thread`: a chat. The user's lines on the end side in the accent, the app's on the start side;
  // day headers, runs within 3 minutes, the newest 50. A line's `actions`, `reply` and `dismiss`
  // work as a list item's; `status` is a small line under the newest.
  Component {
    id: threadC
    Column {
      id: thr
      property var b: ({})
      property var boxes: ({})
      function box(key) { return key !== "" && boxes[key] ? boxes[key] : null }
      // The newest 50; `items` may arrive as a list type that isn't a JS Array, so copy by index.
      readonly property var lines: {
        var its = b.items || [], out = []
        for (var i = Math.max(0, its.length - 50); i < its.length; i++) out.push(its[i])
        return out
      }
      spacing: Style.space(2)
      Label {
        visible: thr.lines.length === 0
        text: thr.b.empty || ""
        color: ch.muted
        horizontalAlignment: Text.AlignHCenter
      }
      Repeater {
        model: thr.lines
        Column {
          id: line
          required property var modelData
          required property int index
          readonly property var it: modelData
          readonly property var prev: index > 0 ? thr.lines[index - 1] : null
          readonly property bool mine: it.who === "user"
          readonly property bool newDay: !prev || ch.dayOf(prev.ts) !== ch.dayOf(it.ts)
          readonly property bool joined: !!prev && !newDay && (prev.who === "user") === mine && it.ts - prev.ts < 180
          readonly property color ink: mine ? Color.popups.background : ch.fg
          function sendReply() {
            var t = replyBox.input.text.trim()
            if (!t || !it.reply) return
            var u = ch.uid()
            ch.send({ action: it.reply.id, uid: u, value: { item: it.id, text: t } })
            if (ch.ui) ch.ui.addPending(ch.name, thr.b.id + "/" + it.id, u, t, it.id)
            ch.clearDrafts(thr.b.id + "/" + it.id)
            replyBox.input.text = ""
            ch.focusKey = replyBox.key
          }
          visible: !ch.dismissed[thr.b.id + "/" + it.id]
          width: thr.width
          topPadding: joined ? 0 : Style.space(5)
          spacing: Style.space(3)

          Label {
            visible: line.newDay
            text: ch.dayLabel(line.it.ts)
            color: ch.muted
            small: true
            horizontalAlignment: Text.AlignHCenter
            topPadding: Style.space(6)
          }
          Item {
            width: parent.width
            height: bubble.height
            HoverHandler { id: lineHover }
            Rectangle {
              id: bubble
              readonly property real maxW: parent.width * 0.82
              readonly property real inner: Style.space(10)
              anchors.right: line.mine ? parent.right : undefined
              anchors.left: line.mine ? undefined : parent.left
              width: Math.min(maxW, Math.max(natural.implicitWidth, timeText.implicitWidth,
                                             actionsRow.visible ? actionsRow.implicitWidth : 0,
                                             line.it.reply ? maxW : 0) + inner * 2)
              height: bubbleCol.implicitHeight + Style.space(12)
              radius: Math.max(ch.radius2, Style.space(9))
              color: line.mine ? ch.accent : Util.alpha(ch.fg, 0.08)
              // The text's own width (its longest line), for a bubble no wider than it needs.
              Text {
                id: natural
                visible: false
                text: lineText.text
                font: lineText.font
              }
              Column {
                id: bubbleCol
                anchors { left: parent.left; right: parent.right; verticalCenter: parent.verticalCenter; margins: bubble.inner }
                spacing: Style.space(4)
                TextEdit {
                  id: lineText
                  width: parent.width
                  text: line.it.text || ""
                  readOnly: true
                  selectByMouse: true
                  wrapMode: TextEdit.Wrap
                  color: line.ink
                  selectionColor: Style.selectionFillFor(ch.fg, ch.accent)
                  selectedTextColor: ch.fg
                  horizontalAlignment: ch.alignOf(text)
                  font.family: ch.fontFamily
                  font.pixelSize: Style.font.bodySmall
                }
                Text {
                  id: timeText
                  width: parent.width
                  text: Qt.formatTime(new Date(line.it.ts * 1000), "HH:mm")
                  color: Util.alpha(line.ink, 0.6)
                  horizontalAlignment: Text.AlignRight
                  font.family: "monospace"
                  font.pixelSize: Style.font.caption
                }
                Row {
                  id: actionsRow
                  width: parent.width
                  spacing: Style.space(5)
                  visible: !!(line.it.actions && line.it.actions.length)
                  Repeater {
                    model: line.it.actions || []
                    Btn {
                      required property var modelData
                      readonly property string key: thr.b.id + "/" + line.it.id + "/" + modelData.id
                      label: ch.pressLabel(key, modelData)
                      style: ch.armed === key ? "danger" : (modelData.style || "plain")
                      small: true
                      onClicked: ch.press(key, modelData.confirm, { action: modelData.id, value: { item: line.it.id } })
                    }
                  }
                }
                Repeater {
                  model: line.it.reply && ch.ui ? ch.ui.pendingFor(ch.name, thr.b.id + "/" + line.it.id) : []
                  Label { required property var modelData; text: "⏳ " + modelData.text; color: ch.muted }
                }
                Row {
                  width: parent.width
                  spacing: Style.space(6)
                  visible: !!line.it.reply
                  Box {
                    id: replyBox
                    width: parent.width - replyBtn.width - parent.spacing
                    key: thr.b.id + "/" + line.it.id + "/reply"
                    placeholder: line.it.reply && line.it.reply.placeholder ? line.it.reply.placeholder : "Answer…"
                    onEnter: line.sendReply()
                    Component.onCompleted: { var m = thr.boxes; m[key] = input; thr.boxes = m }
                  }
                  Btn {
                    id: replyBtn
                    anchors.bottom: parent.bottom
                    label: line.it.reply && line.it.reply.submit ? line.it.reply.submit : "Send"
                    style: "primary"
                    small: true
                    onClicked: line.sendReply()
                  }
                }
              }
            }
            // `dismiss`: a small ✕ beside the bubble while the pointer is on the line.
            IconBtn {
              visible: lineHover.hovered && !!(line.it.dismiss && line.it.dismiss.id)
              anchors.verticalCenter: bubble.verticalCenter
              anchors.right: line.mine ? bubble.left : undefined
              anchors.left: line.mine ? undefined : bubble.right
              glyph: "󰅖"
              small: true
              onClicked: ch.dismiss(thr.b.id, line.it.id, line.it.dismiss.id)
            }
          }
        }
      }
      Label {
        visible: !!thr.b.status
        text: thr.b.status || ""
        color: ch.muted
        small: true
        topPadding: Style.space(3)
      }
    }
  }

  Component {
    id: checklistC
    Column {
      property var b: ({})
      function box() { return null }
      spacing: Style.space(3)
      Repeater {
        model: b.items || []
        Item {
          id: crow
          required property var modelData
          // In auto the controls stay put (box left, actions right); only the label's text aligns to its own side.
          LayoutMirroring.enabled: ch.autoDir ? false : ch.rtl
          LayoutMirroring.childrenInherit: true
          width: parent ? parent.width : 0
          height: Math.max(cbText.implicitHeight, Style.space(22), cbActions.visible ? cbActions.height : 0)
          Rectangle {
            id: cb
            width: Style.space(16); height: width; radius: 3
            anchors { left: parent.left; verticalCenter: parent.verticalCenter }   // mirrors in rtl
            color: crow.modelData.checked ? ch.accent : "transparent"
            border.color: crow.modelData.checked ? ch.accent : ch.muted
            Text { anchors.centerIn: parent; visible: crow.modelData.checked; text: "✓"; color: Color.popups.background; font.pixelSize: Style.font.caption; font.bold: true }
          }
          Label {
            id: cbText
            anchors { left: cb.right; leftMargin: Style.space(8); verticalCenter: parent.verticalCenter }
            anchors.right: cbActions.visible ? cbActions.left : parent.right
            anchors.rightMargin: cbActions.visible ? Style.space(6) : 0
            text: crow.modelData.label || ""
            color: crow.modelData.checked ? ch.muted : ch.fg
          }
          // The box and its label take the tap, not the action buttons beside them (a nested
          // TapHandler would fire too).
          Item {
            anchors { left: parent.left; right: cbText.right; top: parent.top; bottom: parent.bottom }
            TapHandler { onTapped: ch.send({ action: b.id, value: { item: crow.modelData.id, checked: !crow.modelData.checked } }) }
            HoverHandler { cursorShape: Qt.PointingHandCursor }
          }
          // The item's own actions (the built-in list's ✕), sent and confirmed like a list row's.
          Row {
            id: cbActions
            anchors { right: parent.right; verticalCenter: parent.verticalCenter }
            spacing: Style.space(5)
            visible: !!(crow.modelData.actions && crow.modelData.actions.length)
            Repeater {
              model: crow.modelData.actions || []
              Btn {
                required property var modelData
                readonly property string key: b.id + "/" + crow.modelData.id + "/" + modelData.id
                label: ch.pressLabel(key, modelData)
                style: ch.armed === key ? "danger" : "plain"
                small: true
                onClicked: ch.press(key, modelData.confirm, { action: modelData.id, value: { item: crow.modelData.id } })
              }
            }
          }
        }
      }
    }
  }

  Component {
    id: composeC
    Column {
      id: comp
      property var b: ({})
      readonly property var chosen: ch.draft(b.id + "/chips", [])
      readonly property var pending: ch.ui ? ch.ui.pendingFor(ch.name, b.id) : []
      function box(key) { return key === "" || key === b.id + "/text" ? field.input : null }
      function submit() {
        var t = field.input.text.trim()
        if (!t) return
        var u = ch.uid()
        ch.send({ action: b.id, uid: u, value: { text: t, chips: chosen } })
        if (ch.ui) ch.ui.addPending(ch.name, b.id, u, t)
        ch.clearDrafts(b.id)
        field.input.text = ""
        ch.focusKey = b.id + "/text"
      }
      spacing: Style.space(6)

      // Sent, not yet in a view (⏳).
      Repeater {
        model: comp.pending
        Label { required property var modelData; width: comp.width; text: "⏳ " + modelData.text; color: ch.muted }
      }
      Flow {
        width: parent.width
        spacing: Style.space(4)
        visible: !!(comp.b.chips && comp.b.chips.length)
        Repeater {
          model: comp.b.chips || []
          Chip {
            required property var modelData
            label: modelData.label
            on: comp.chosen.indexOf(modelData.id) >= 0
            onClicked: {
              var c = comp.chosen.slice(), i = c.indexOf(modelData.id)
              if (i >= 0) c.splice(i, 1); else if (comp.b.multi === false) c = [modelData.id]; else c.push(modelData.id)
              ch.setDraft(comp.b.id + "/chips", c)
            }
          }
        }
      }
      Row {
        width: parent.width
        spacing: Style.space(6)
        Box {
          id: field
          width: parent.width - sendBtn.width - parent.spacing
          key: comp.b.id + "/text"
          placeholder: comp.b.placeholder || ""
          onEnter: comp.submit()
        }
        Btn { id: sendBtn; anchors.bottom: parent.bottom; label: comp.b.submit || "Send"; style: "primary"; onClicked: comp.submit() }
      }
    }
  }

  Component {
    id: formC
    Column {
      id: form
      property var b: ({})
      property var boxes: ({})
      function box(key) {
        for (var k in boxes) if (key === "" || key === k) return boxes[k]
        return null
      }
      function submit() {
        var f = ({})
        var fs = b.fields || []
        for (var i = 0; i < fs.length; i++) f[fs[i].id] = ch.draft(b.id + "/" + fs[i].id, fs[i].value)
        ch.send({ action: b.id, fields: f })
        ch.clearDrafts(b.id)
        ch.focusKey = ""
      }
      spacing: Style.space(6)
      Repeater {
        model: form.b.fields || []
        Column {
          required property var modelData
          width: form.width
          spacing: Style.space(3)
          Label { width: parent.width; text: modelData.label || ""; small: true; color: ch.muted }
          Box {
            width: parent.width
            key: form.b.id + "/" + modelData.id
            initial: modelData.value || ""
            placeholder: modelData.placeholder || ""
            // A one-line field sends on Enter; a multi-line one needs the button.
            enterSends: !modelData.multi
            onEnter: form.submit()
            Component.onCompleted: { var m = form.boxes; m[key] = input; form.boxes = m }
          }
        }
      }
      Row {
        width: parent.width       // so the button sits at the start side in rtl too
        Btn { label: form.b.submit || "Send"; style: "primary"; onClicked: form.submit() }
      }
    }
  }

  Component {
    id: progressC
    Rectangle {
      property var b: ({})
      function box() { return null }
      height: Math.max(pt.implicitHeight, cancelBtn.height) + Style.space(14)
      radius: ch.radius2
      color: Util.alpha(ch.accent, 0.12)
      Text {
        id: spin
        anchors { left: parent.left; leftMargin: Style.space(10); verticalCenter: parent.verticalCenter }
        text: "󰑓"
        color: ch.accent
        font.pixelSize: Style.font.subtitle
        RotationAnimation on rotation { from: 0; to: 360; duration: 1200; loops: Animation.Infinite; running: spin.visible }
      }
      Label {
        id: pt
        anchors { left: spin.right; leftMargin: Style.space(8); right: cancelBtn.left; rightMargin: Style.space(8); verticalCenter: parent.verticalCenter }
        text: b.text || ""
      }
      Btn {
        id: cancelBtn
        visible: !!b.cancel
        anchors { right: parent.right; rightMargin: Style.space(6); verticalCenter: parent.verticalCenter }
        label: b.cancel ? b.cancel.label : ""
        small: true
        onClicked: ch.send({ action: b.cancel.id })
      }
    }
  }

  Component {
    id: buttonsC
    Flow {
      property var b: ({})
      function box() { return null }
      spacing: Style.space(6)
      Repeater {
        model: b.items || []
        Btn {
          required property var modelData
          readonly property string key: "btn/" + modelData.id
          label: ch.pressLabel(key, modelData)
          style: ch.armed === key ? "danger" : (modelData.style || "plain")
          onClicked: ch.press(key, modelData.confirm, { action: modelData.id })
        }
      }
    }
  }

  // --- Small parts ------------------------------------------------------------------------------

  component Label: Text {
    property bool small: false
    property bool bold: false
    property bool flipped: false          // sits in a row that auto mirrored
    width: parent ? parent.width : implicitWidth
    color: ch.fg
    wrapMode: Text.Wrap
    horizontalAlignment: ch.alignOf(text, flipped)
    font.family: ch.fontFamily
    font.pixelSize: small ? Style.font.caption : Style.font.bodySmall
    font.bold: bold
  }

  component Btn: Rectangle {
    id: btn
    property string label: ""
    property string style: "plain"        // primary | danger | plain
    property bool small: false
    signal clicked()
    width: btnText.implicitWidth + Style.space(small ? 14 : 20)
    height: Style.space(small ? 22 : 28)
    radius: ch.radius2
    readonly property color base: style === "danger" ? "#e5534b" : ch.accent
    color: style === "plain" ? (btnHover.hovered ? Util.alpha(ch.fg, 0.14) : Util.alpha(ch.fg, 0.08))
      : (btnHover.hovered ? Qt.lighter(base, 1.12) : base)
    Text {
      id: btnText
      anchors.centerIn: parent
      text: btn.label
      color: btn.style === "plain" ? ch.fg : Color.popups.background
      font.family: ch.fontFamily
      font.pixelSize: btn.small ? Style.font.caption : Style.font.bodySmall
      font.bold: btn.style !== "plain"
    }
    HoverHandler { id: btnHover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: btn.clicked() }
  }

  component Chip: Rectangle {
    id: chip
    property string label: ""
    property bool on: false
    signal clicked()
    width: chipText.implicitWidth + Style.space(14)
    height: Style.space(20)
    radius: height / 2
    color: on ? Util.alpha(ch.accent, 0.35) : Util.alpha(ch.fg, 0.07)
    border.color: on ? ch.accent : "transparent"
    Text {
      id: chipText
      anchors.centerIn: parent
      text: chip.label
      color: ch.fg
      font.family: ch.fontFamily
      font.pixelSize: Style.font.caption
    }
    HoverHandler { cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: chip.clicked() }
  }

  component IconBtn: Item {
    id: ib
    property string glyph: ""
    property bool small: false
    signal clicked()
    width: Style.space(small ? 22 : 30)
    height: width
    Rectangle { anchors.fill: parent; radius: ch.radius2; color: ibHover.hovered ? Util.alpha(ch.fg, 0.1) : "transparent" }
    Text {
      anchors.centerIn: parent
      text: ib.glyph
      color: ch.fg
      font.family: ch.fontFamily
      font.pixelSize: ib.small ? Style.font.body : Style.font.subtitle + Style.space(2)
    }
    HoverHandler { id: ibHover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: ib.clicked() }
  }

  // A text box that keeps its text in `drafts`. Enter sends (Shift+Enter: a new line) unless
  // `enterSends` is off; Esc closes the panel.
  component Box: Item {
    id: bx
    property string key: ""
    property string initial: ""
    property string placeholder: ""
    property bool enterSends: true
    property alias input: area
    signal enter()
    readonly property int lineH: Math.round(area.font.pixelSize * 1.35)
    height: Math.min(lineH * 6, Math.max(lineH, area.contentHeight)) + area.topPadding + area.bottomPadding + 2

    Rectangle {
      anchors.fill: parent
      radius: ch.radius2
      color: Util.alpha(ch.fg, area.activeFocus ? 0.1 : 0.06)
      border.color: area.activeFocus ? ch.accent : Util.alpha(ch.fg, 0.15)
    }
    Flickable {
      anchors.fill: parent
      anchors.margins: 1
      clip: true
      boundsBehavior: Flickable.StopAtBounds
      QQC.TextArea.flickable: QQC.TextArea {
        id: area
        text: ch.draft(bx.key, bx.initial)
        placeholderText: bx.placeholder
        wrapMode: TextEdit.Wrap
        color: ch.fg
        selectionColor: Style.selectionFillFor(ch.fg, ch.accent)
        selectedTextColor: ch.fg
        placeholderTextColor: ch.muted
        horizontalAlignment: ch.alignOf(text !== "" ? text : placeholderText)
        font.family: ch.fontFamily
        font.pixelSize: Style.font.bodySmall
        leftPadding: Style.space(8)
        rightPadding: Style.space(8)
        topPadding: Style.space(5)
        bottomPadding: Style.space(5)
        background: null
        onTextChanged: if (text !== ch.draft(bx.key, bx.initial)) ch.setDraft(bx.key, text)
        onActiveFocusChanged: if (activeFocus) { ch.focusKey = bx.key; ch.focusedBox = bx; Qt.callLater(ch.reveal, bx) }
        Keys.onPressed: function(event) {
          if (event.key === Qt.Key_Escape) {
            event.accepted = true
            ui.close()
          } else if (bx.enterSends && (event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                     && !(event.modifiers & Qt.ShiftModifier)) {
            event.accepted = true
            bx.enter()
          }
        }
      }
    }
  }
}
