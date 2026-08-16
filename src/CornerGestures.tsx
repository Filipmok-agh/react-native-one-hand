import { useEffect, useRef, type PropsWithChildren } from 'react';
import { StyleSheet, useWindowDimensions, View, type GestureResponderEvent } from 'react-native';
import { useOneHand, type OneHandSide } from './OneHandContext';

/** Configuration of the corner activation gestures. */
export type CornerGesturesConfig = {
  /** Long-press in the BOTTOM-LEFT corner docks the app to the left. Enabled by default. */
  left?: boolean;
  /** Long-press in the BOTTOM-RIGHT corner docks the app to the right. Enabled by default. */
  right?: boolean;
};

/** Side length (pt) of the square hot zone anchored at each bottom corner. */
const CORNER_SIZE = 56;
/** Press-and-hold duration (ms) required to activate the mode. */
const LONG_PRESS_MS = 450;
/** Finger movement beyond this threshold cancels the gesture (it was not a hold). */
const MOVE_SLOP = 16;

/**
 * Corner gesture layer. Observes touches WITHOUT claiming them: the layer listens via the
 * passive `onTouchStart/Move/End` props, which bubble to ancestors regardless of which
 * view holds the responder. Regular taps therefore pass through to the UI underneath even
 * inside the corner hot zones (an earlier capture-phase implementation swallowed them —
 * buttons whose edges reached a corner appeared dead); a press held for ~450 ms in an
 * enabled corner activates the mode. The tradeoff: a child sitting exactly in a corner may
 * react to the same long press — acceptable, since the zones are the physical screen
 * corners.
 *
 * Touch coordinates are in the app window's coordinate space, so the layer behaves
 * identically while the window is scaled down: the app's corner on the docking side
 * coincides with the physical corner of the screen (and holding the opposite corner
 * switches sides, because `enable(side)` also updates the side).
 */
export function CornerGestures({
  children,
  left = true,
  right = true,
}: PropsWithChildren<CornerGesturesConfig>) {
  const { enable } = useOneHand();
  const { width, height } = useWindowDimensions();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const start = useRef({ x: 0, y: 0 });

  const clear = () => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  };

  // Do not fire a pending activation after unmount.
  useEffect(() => clear, []);

  const cornerAt = (x: number, y: number): OneHandSide | null => {
    if (y < height - CORNER_SIZE) return null;
    if (right && x > width - CORNER_SIZE) return 'right';
    if (left && x < CORNER_SIZE) return 'left';
    return null;
  };

  if (!left && !right) {
    return <View style={styles.fill}>{children}</View>;
  }

  return (
    <View
      style={styles.fill}
      onTouchStart={(e: GestureResponderEvent) => {
        const { pageX, pageY } = e.nativeEvent;
        clear();
        const corner = cornerAt(pageX, pageY);
        if (!corner) return;
        start.current = { x: pageX, y: pageY };
        timer.current = setTimeout(() => enable(corner), LONG_PRESS_MS);
      }}
      onTouchMove={(e: GestureResponderEvent) => {
        if (!timer.current) return;
        const dx = Math.abs(e.nativeEvent.pageX - start.current.x);
        const dy = Math.abs(e.nativeEvent.pageY - start.current.y);
        if (dx > MOVE_SLOP || dy > MOVE_SLOP) clear();
      }}
      onTouchEnd={clear}
      onTouchCancel={clear}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: {
    flex: 1,
  },
});
