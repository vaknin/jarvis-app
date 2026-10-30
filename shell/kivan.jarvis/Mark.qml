// A channel's icon (`icon` in `jarvis-link channels --json`, from the daemon's mark.rs): Lucide-style
// shapes in `mark.view`, fitted into this item and drawn in `color` (docs/DESIGN.md).
import QtQuick
import QtQuick.Shapes

Item {
  id: m

  property var mark: null
  property color color: "white"

  readonly property var view: mark && mark.view ? mark.view : [0, 0, 24, 24]
  readonly property real k: Math.min(width, height) / Math.max(view[2], view[3])
  readonly property var strokes: mark ? mark.paths.filter(p => p.stroke > 0) : []
  readonly property var fills: mark ? mark.paths.filter(p => p.fill) : []

  Shape {
    x: (m.width - m.view[2] * m.k) / 2 - m.view[0] * m.k
    y: (m.height - m.view[3] * m.k) / 2 - m.view[1] * m.k
    scale: m.k
    transformOrigin: Item.TopLeft
    preferredRendererType: Shape.CurveRenderer

    ShapePath {
      strokeColor: m.strokes.length ? m.color : "transparent"
      strokeWidth: m.strokes.length ? m.strokes[0].stroke : 0
      fillColor: "transparent"
      capStyle: ShapePath.RoundCap
      joinStyle: ShapePath.RoundJoin
      PathSvg { path: m.strokes.map(p => p.d).join(" ") }
    }
    ShapePath {
      strokeColor: "transparent"
      fillColor: m.fills.length ? m.color : "transparent"
      PathSvg { path: m.fills.map(p => p.d).join(" ") }
    }
  }
}
