// The chat card, drawn the same way in the bar dropdown (about 330×400) and the SUPER+M panel
// (about 440×470): header with the phone's link state, the conversation, and the input. `ui` is
// Panel.qml, which holds the messages and runs the commands.
import QtQuick
import QtQuick.Controls as QQC
import qs.Commons
import qs.Ui

BorderSurface {
  id: chat

  property var ui: null
  property bool roomy: false          // the centered panel: a little more air and a key hint

  readonly property color fg: Color.popups.text
  readonly property color muted: Util.alpha(fg, 0.55)
  readonly property color theirsFill: Util.alpha(fg, 0.08)
  readonly property color mineFill: Util.alpha(Color.accent, 0.24)
  readonly property string fontFamily: Style.font.family
  readonly property int radius2: Math.max(4, Style.cornerRadius)
  readonly property int pad: roomy ? Style.spacing.xl : Style.spacing.lg
  readonly property int bubblePad: Style.space(7)
  readonly property var imageExt: ["png", "jpg", "jpeg", "webp", "gif", "bmp"]

  color: Color.popups.background
  borderSpec: Border.localOrSurfaceSpec("popups", "border", Color.popups.border, Color.popups.border, Math.max(1, Style.space(2)))
  radius: Style.cornerRadius

  function focusInput() { input.forceActiveFocus() }
  function scrollToEnd() { list.positionViewAtEnd() }
  function pasteText() { input.paste() }

  // Images pasted with Ctrl+V, waiting for Enter.
  property var attachments: []
  function attach(path) { attachments = attachments.concat([path]); focusInput() }
  function detach(i) { var a = attachments.slice(); a.splice(i, 1); attachments = a }

  function isImage(name) {
    var dot = name ? name.lastIndexOf(".") : -1
    return dot >= 0 && imageExt.indexOf(name.slice(dot + 1).toLowerCase()) >= 0
  }

  function sizeLabel(n) {
    if (!(n > 0)) return ""
    if (n < 1024) return n + " B"
    if (n < 1024 * 1024) return Math.round(n / 1024) + " KB"
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB"
    return (n / 1024 / 1024 / 1024).toFixed(2) + " GB"
  }

  function dayLabel(day) {
    var now = new Date()
    var today = Qt.formatDate(now, "yyyy-MM-dd")
    var y = new Date(now.getTime() - 86400000)
    if (day === today) return "Today"
    if (day === Qt.formatDate(y, "yyyy-MM-dd")) return "Yesterday"
    var d = new Date(day + "T12:00:00")
    return Qt.formatDate(d, d.getFullYear() === now.getFullYear() ? "ddd d MMM" : "d MMM yyyy")
  }

  // Plain text to StyledText: escaped, links made clickable, newlines kept.
  function rich(s) {
    var t = String(s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    t = t.replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])/g, '<a href="$1">$1</a>')
    return t.replace(/\n/g, "<br>")
  }

  // --- Header -----------------------------------------------------------------------------------

  Item {
    id: header
    anchors { top: parent.top; left: parent.left; right: parent.right }
    anchors.topMargin: chat.contentTopInset
    anchors.leftMargin: chat.contentLeftInset + chat.pad
    anchors.rightMargin: chat.contentRightInset + chat.pad / 2
    height: Style.space(34)

    Rectangle {
      id: dot
      width: Style.space(8); height: width; radius: width / 2
      anchors.verticalCenter: parent.verticalCenter
      color: ui && ui.status.connected ? "#4cc38a" : chat.muted
    }

    Text {
      id: peerName
      anchors { left: dot.right; leftMargin: Style.space(7); verticalCenter: parent.verticalCenter }
      text: ui ? ui.peerName : ""
      color: chat.fg
      font.family: chat.fontFamily
      font.pixelSize: Style.font.subtitle
      font.bold: true
    }

    Text {
      anchors { left: peerName.right; leftMargin: Style.space(8); right: actions.left; rightMargin: Style.space(6) }
      anchors.baseline: peerName.baseline
      text: ui ? ui.statusLine : ""
      color: chat.muted
      wrapMode: Text.Wrap
      font.family: chat.fontFamily
      font.pixelSize: Style.font.caption
    }

    Row {
      id: actions
      anchors { right: parent.right; verticalCenter: parent.verticalCenter }
      spacing: Style.space(2)
      IconButton { glyph: "󰂞"; tip: "Ring the phone"; onClicked: ui.ring() }
      IconButton { glyph: "󰅖"; tip: "Close (Esc)"; onClicked: ui.close() }
    }
  }

  Rectangle {
    id: headerLine
    anchors { top: header.bottom; left: parent.left; right: parent.right }
    anchors.leftMargin: chat.contentLeftInset
    anchors.rightMargin: chat.contentRightInset
    height: 1
    color: Util.alpha(chat.fg, 0.1)
  }

  // --- Conversation -----------------------------------------------------------------------------

  ListView {
    id: list
    anchors { top: headerLine.bottom; left: parent.left; right: parent.right; bottom: inputArea.top }
    anchors.leftMargin: chat.contentLeftInset + chat.pad
    anchors.rightMargin: chat.contentRightInset + chat.pad
    clip: true
    model: ui ? ui.messages : null
    boundsBehavior: Flickable.StopAtBounds
    header: Item { height: chat.pad }
    footer: Item { height: chat.pad }
    QQC.ScrollBar.vertical: QQC.ScrollBar { policy: QQC.ScrollBar.AsNeeded; width: Style.space(5) }

    section.property: "day"
    section.delegate: Item {
      required property string section
      width: ListView.view.width
      height: Style.space(28)
      Text {
        anchors.centerIn: parent
        text: chat.dayLabel(parent.section)
        color: chat.muted
        font.family: chat.fontFamily
        font.pixelSize: Style.font.caption
      }
    }

    // Stay at the bottom as messages arrive, unless scrolled up to read.
    property bool pinned: true
    onMovementEnded: pinned = atYEnd
    onCountChanged: if (pinned) Qt.callLater(positionViewAtEnd)
    onContentHeightChanged: if (pinned && !moving) Qt.callLater(positionViewAtEnd)

    delegate: Item {
      id: row
      required property int index
      required property var model
      readonly property bool mine: model.fromMe
      readonly property bool system: model.kind === "ring"
      readonly property bool wantsImage: model.kind === "file" && chat.isImage(model.fileName) && model.path !== ""
      // A file that has since moved or been deleted shows as a chip instead of an empty bubble.
      readonly property bool image: wantsImage && img.status !== Image.Error
      readonly property bool fileChip: model.kind === "file" && !image
      // Same sender within two minutes: tucked under the previous bubble.
      readonly property bool grouped: {
        if (index === 0 || !ui) return false
        var prev = ui.messages.get(index - 1)
        return prev && prev.fromMe === model.fromMe && prev.day === model.day && prev.kind !== "ring"
          && model.ts - prev.ts < 120000
      }
      readonly property int maxBubble: Math.round(list.width * 0.8)
      property bool copied: false

      width: list.width
      height: (system ? sysText.implicitHeight : bubble.height) + (grouped ? Style.space(2) : Style.space(7))

      Text {
        id: sysText
        visible: row.system
        anchors { horizontalCenter: parent.horizontalCenter; bottom: parent.bottom }
        text: "󰂞 Rang the phone · " + Qt.formatTime(new Date(model.ts), "HH:mm")
        color: chat.muted
        font.family: chat.fontFamily
        font.pixelSize: Style.font.caption
      }

      Rectangle {
        id: bubble
        visible: !row.system
        anchors.bottom: parent.bottom
        x: row.mine ? parent.width - width : 0
        width: Math.min(row.maxBubble, content.implicitWidth + chat.bubblePad * 2)
        height: content.implicitHeight + chat.bubblePad * 2
        radius: chat.radius2
        color: row.mine ? chat.mineFill : chat.theirsFill

        MouseArea {
          anchors.fill: parent
          cursorShape: Qt.PointingHandCursor
          acceptedButtons: Qt.LeftButton | Qt.RightButton
          onClicked: function(mouse) {
            if (row.image || row.fileChip) {
              if (mouse.button === Qt.RightButton) chat.openMenu(bubble.mapToItem(chat, mouse.x, mouse.y), model.path, row.image)
              else ui.openFile(model.path)
            } else {
              ui.copy(model.text)
              row.copied = true
              copiedTimer.restart()
            }
          }
        }
        Timer { id: copiedTimer; interval: 1200; onTriggered: row.copied = false }

        Column {
          id: content
          x: chat.bubblePad
          y: chat.bubblePad
          spacing: Style.space(3)

          Image {
            id: img
            visible: row.image
            // The box the image fits into; the bubble shrinks to the fitted size, so a portrait
            // screenshot gets a narrow bubble instead of a wide one with empty sides.
            readonly property int maxW: Math.min(Style.space(220), row.maxBubble - chat.bubblePad * 2)
            readonly property int maxH: Style.space(220)
            readonly property bool sized: status === Image.Ready && implicitWidth > 0 && implicitHeight > 0
            // Decoded size (aspect kept, from sourceSize below) scaled into the box; small images stay as-is.
            readonly property real fit: sized ? Math.min(1, maxW / implicitWidth, maxH / implicitHeight) : 1
            width: !visible ? 0 : sized ? Math.round(implicitWidth * fit) : maxW
            height: !visible ? 0 : sized ? Math.round(implicitHeight * fit) : Style.space(120)
            source: row.wantsImage ? "file://" + model.path : ""
            sourceSize.width: maxW * 2
            sourceSize.height: maxH * 2
            fillMode: Image.PreserveAspectFit
            horizontalAlignment: row.mine ? Image.AlignRight : Image.AlignLeft
            asynchronous: true
            cache: true
          }

          Row {
            visible: row.fileChip
            spacing: Style.space(7)
            height: visible ? implicitHeight : 0

            Rectangle {
              width: Style.space(26); height: Style.space(30); radius: 2
              color: Color.accent
              Text {
                anchors.centerIn: parent
                text: {
                  var n = model.fileName || ""
                  var dot = n.lastIndexOf(".")
                  return dot > 0 ? n.slice(dot + 1, dot + 5).toUpperCase() : "FILE"
                }
                color: Color.popups.background
                font.family: chat.fontFamily
                font.pixelSize: Style.space(8)
                font.bold: true
              }
            }

            Column {
              anchors.verticalCenter: parent.verticalCenter
              spacing: Style.space(2)
              Text {
                text: model.fileName || "file"
                width: Math.min(implicitWidth, row.maxBubble - chat.bubblePad * 2 - Style.space(33))
                wrapMode: Text.WrapAnywhere
                color: chat.fg
                font.family: chat.fontFamily
                font.pixelSize: Style.font.bodySmall
              }
              Text {
                text: model.total > 0 && model.done < model.total
                  ? Math.floor(100 * model.done / model.total) + "% of " + chat.sizeLabel(model.total)
                  : chat.sizeLabel(model.fileSize)
                color: chat.muted
                font.family: chat.fontFamily
                font.pixelSize: Style.font.caption
              }
            }
          }

          // A transfer under way (either direction).
          Rectangle {
            visible: model.kind === "file" && model.total > 0 && model.done < model.total
            width: visible ? Math.max(Style.space(120), content.width) : 0
            height: visible ? Style.space(3) : 0
            color: Util.alpha(chat.fg, 0.15)
            Rectangle {
              height: parent.height
              width: parent.width * (model.total > 0 ? model.done / model.total : 0)
              color: Color.accent
            }
          }

          Text {
            id: body
            visible: model.kind === "text" || model.kind === "ping"
            readonly property int maxW: row.maxBubble - chat.bubblePad * 2
            width: visible ? Math.min(implicitWidth, maxW) : 0
            height: visible ? implicitHeight : 0
            text: (model.kind === "ping" ? "󰂚 " : "") + chat.rich(model.text)
            textFormat: Text.StyledText
            wrapMode: Text.Wrap
            color: chat.fg
            linkColor: Color.accent
            font.family: chat.fontFamily
            font.pixelSize: Style.font.body
            onLinkActivated: function(link) { Qt.openUrlExternally(link) }
            HoverHandler { cursorShape: body.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
          }

          Row {
            anchors.right: parent.right
            spacing: Style.space(8)

            // Stops a file that is still sending (or waiting to); the phone drops what it got.
            Text {
              visible: row.mine && model.kind === "file" && model.state === "queued"
              text: "✕ Cancel"
              color: cancelArea.containsMouse ? Color.urgent : chat.muted
              font.family: chat.fontFamily
              font.pixelSize: Style.font.caption
              MouseArea {
                id: cancelArea
                anchors { fill: parent; margins: -Style.space(4) }
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: ui.cancel(model.mid)
              }
            }

            Text {
              id: meta
              text: {
                var t = Qt.formatTime(new Date(model.ts), "HH:mm")
                if (row.copied) return "󰄬 Copied"
                if (model.state === "cancelled") return t + "  cancelled"
                if (!row.mine) return t
                if (model.state === "queued") return t + "  󰥔"
                if (model.state === "expired") return t + "  not delivered"
                return t + "  ✓✓"
              }
              color: row.copied ? Color.accent : model.state === "expired" ? Color.urgent : chat.muted
              font.family: chat.fontFamily
              font.pixelSize: Style.font.caption
            }
          }
        }
      }
    }

    Text {
      anchors.centerIn: parent
      visible: list.count === 0
      width: parent.width
      horizontalAlignment: Text.AlignHCenter
      wrapMode: Text.Wrap
      text: !ui || !ui.daemonUp ? "Jarvis isn't running.\nsystemctl --user start jarvis-link"
        : !ui.status.peer ? "Not paired.\nRun jarvis-link pair in a terminal."
        : "No messages yet."
      color: chat.muted
      font.family: chat.fontFamily
      font.pixelSize: Style.font.bodySmall
    }
  }

  // --- Input ------------------------------------------------------------------------------------

  Item {
    id: inputArea
    anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
    anchors.leftMargin: chat.contentLeftInset + chat.pad / 2
    anchors.rightMargin: chat.contentRightInset + chat.pad / 2
    anchors.bottomMargin: chat.contentBottomInset + chat.pad / 2
    height: inputRow.y + inputRow.height + (hint.visible ? hint.height + Style.space(4) : 0)

    // Pasted images, sent with the next Enter; ✕ drops one.
    Row {
      id: attachStrip
      visible: chat.attachments.length > 0
      height: visible ? Style.space(48) : 0
      spacing: Style.space(6)

      Repeater {
        model: chat.attachments
        Item {
          required property string modelData
          required property int index
          width: Style.space(48)
          height: Style.space(48)

          Image {
            anchors.fill: parent
            source: "file://" + parent.modelData
            sourceSize.width: width * 2
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
          }

          Rectangle {
            anchors { top: parent.top; right: parent.right; margins: Style.space(2) }
            width: Style.space(16)
            height: width
            radius: width / 2
            color: Util.alpha(Color.popups.background, 0.85)
            Text {
              anchors.centerIn: parent
              text: "✕"
              color: chat.fg
              font.pixelSize: Style.space(9)
            }
            MouseArea {
              anchors { fill: parent; margins: -Style.space(3) }
              cursorShape: Qt.PointingHandCursor
              onClicked: chat.detach(parent.parent.index)
            }
          }
        }
      }
    }

    Row {
      id: inputRow
      y: attachStrip.visible ? attachStrip.height + Style.space(6) : 0
      width: parent.width
      height: Math.max(Style.space(32), box.height)
      spacing: Style.space(4)

      IconButton {
        id: attach
        anchors.bottom: parent.bottom
        glyph: "󰏢"
        tip: "Send files"
        onClicked: ui.pickFiles()
      }

      Item {
        id: box
        width: inputRow.width - attach.width - send.width - inputRow.spacing * 2
        anchors.bottom: parent.bottom
        // One line to start, up to five, then it scrolls.
        readonly property int lineH: Math.round(input.font.pixelSize * 1.35)
        height: Math.min(lineH * 5, Math.max(lineH, input.contentHeight)) + input.topPadding + input.bottomPadding + Border.top(spec) + Border.bottom(spec)
        readonly property var spec: Border.controlSpec(input.activeFocus ? "focus" : (boxHover.hovered ? "hover-cursor" : "normal"), chat.fg, Color.accent)

        HoverHandler { id: boxHover; cursorShape: Qt.IBeamCursor }

        BorderSurface {
          anchors.fill: parent
          color: Style.controlFill(input.activeFocus, boxHover.hovered, chat.fg, Color.accent)
          borderSpec: box.spec
          radius: chat.radius2
        }

        Flickable {
          id: flick
          anchors.fill: parent
          anchors.margins: Border.top(box.spec)
          clip: true
          boundsBehavior: Flickable.StopAtBounds

          QQC.TextArea.flickable: QQC.TextArea {
            id: input
            placeholderText: ui ? "Message " + ui.peerName + "…" : ""
            wrapMode: TextEdit.Wrap
            color: chat.fg
            selectionColor: Style.selectionFillFor(chat.fg, Color.accent)
            selectedTextColor: chat.fg
            placeholderTextColor: chat.muted
            font.family: chat.fontFamily
            font.pixelSize: Style.font.body
            leftPadding: Style.space(9)
            rightPadding: Style.space(9)
            topPadding: Style.space(6)
            bottomPadding: Style.space(6)
            background: null

            // Enter sends, Shift+Enter adds a line, Ctrl+V attaches a clipboard image (or pastes
            // text), Esc closes.
            Keys.onPressed: function(event) {
              if (event.key === Qt.Key_Escape) {
                event.accepted = true
                ui.close()
              } else if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                         && !(event.modifiers & Qt.ShiftModifier)) {
                event.accepted = true
                chat.submit()
              } else if (event.matches(StandardKey.Paste)) {
                event.accepted = true
                ui.paste()
              }
            }
          }
        }
      }

      IconButton {
        id: send
        anchors.bottom: parent.bottom
        glyph: "󰒊"
        tip: "Send (Enter)"
        accent: input.text.trim() !== ""
        onClicked: chat.submit()
      }
    }

    Text {
      id: hint
      visible: chat.roomy
      anchors { top: inputRow.bottom; topMargin: Style.space(4); horizontalCenter: parent.horizontalCenter }
      width: Math.min(implicitWidth, parent.width)
      wrapMode: Text.Wrap
      text: "Enter sends · Shift+Enter line · Ctrl+V attaches · right-click menu"
      color: Util.alpha(chat.fg, 0.4)
      font.family: chat.fontFamily
      font.pixelSize: Style.font.caption
    }
  }

  // --- Right-click menu for files and images ------------------------------------------------------

  MouseArea {
    anchors.fill: parent
    visible: fileMenu.visible
    z: 9
    acceptedButtons: Qt.LeftButton | Qt.RightButton
    onClicked: fileMenu.visible = false
  }

  Rectangle {
    id: fileMenu
    property string path: ""
    property bool image: false
    visible: false
    z: 10
    width: menuCol.implicitWidth + Style.space(8)
    height: menuCol.implicitHeight + Style.space(8)
    radius: 6
    color: Color.popups.background
    border.color: Util.alpha(chat.fg, 0.2)

    Column {
      id: menuCol
      anchors.centerIn: parent
      MenuEntry { label: "Open"; onClicked: ui.openFile(fileMenu.path) }
      MenuEntry {
        label: fileMenu.image ? "Copy image" : "Copy path"
        onClicked: fileMenu.image ? ui.copyImage(fileMenu.path) : ui.copy(fileMenu.path)
      }
    }
  }

  function openMenu(p, path, image) {
    if (!path) return
    fileMenu.path = path
    fileMenu.image = image
    fileMenu.x = Math.max(0, Math.min(p.x, width - fileMenu.width))
    fileMenu.y = Math.max(0, Math.min(p.y, height - fileMenu.height))
    fileMenu.visible = true
  }

  component MenuEntry: Rectangle {
    id: entry
    property string label: ""
    signal clicked()
    width: Style.space(120)
    height: Style.space(26)
    radius: 4
    color: entryArea.containsMouse ? Util.alpha(Color.accent, 0.25) : "transparent"
    Text {
      anchors.verticalCenter: parent.verticalCenter
      x: Style.space(8)
      text: entry.label
      color: chat.fg
      font.family: chat.fontFamily
      font.pixelSize: Style.font.bodySmall
    }
    MouseArea {
      id: entryArea
      anchors.fill: parent
      hoverEnabled: true
      cursorShape: Qt.PointingHandCursor
      onClicked: { fileMenu.visible = false; entry.clicked() }
    }
  }

  function submit() {
    var t = input.text.trim()
    if (attachments.length > 0) {
      ui.sendWith(attachments, t)
      attachments = []
    } else if (t) {
      ui.sendText(t)
    } else {
      return
    }
    input.clear()
    list.pinned = true
  }

  component IconButton: Item {
    id: ib
    property string glyph: ""
    property string tip: ""
    property bool accent: false
    signal clicked()
    width: Style.space(30)
    height: Style.space(30)

    Rectangle {
      anchors.fill: parent
      radius: chat.radius2
      color: ib.accent ? Color.accent : (ibHover.hovered ? Util.alpha(chat.fg, 0.1) : "transparent")
    }
    Text {
      anchors.centerIn: parent
      text: ib.glyph
      color: ib.accent ? Color.popups.background : chat.fg
      font.family: chat.fontFamily
      font.pixelSize: Style.font.subtitle + Style.space(2)
    }
    HoverHandler { id: ibHover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: ib.clicked() }
  }
}
