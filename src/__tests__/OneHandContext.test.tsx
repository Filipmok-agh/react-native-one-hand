import { render, act } from '@testing-library/react-native';
import { Dimensions, Keyboard } from 'react-native';
import {
  OneHandProvider,
  useOneHand,
  DEFAULT_ONE_HAND_SCALE,
  type OneHandValue,
} from '../OneHandContext';

type KeyboardEvent =
  'keyboardWillShow' | 'keyboardDidShow' | 'keyboardWillHide' | 'keyboardDidHide';

let keyboardListeners: Partial<Record<KeyboardEvent, Array<() => void>>>;
let dimensionListeners: Array<(event: { window: { width: number; height: number } }) => void>;
let windowSize: { width: number; height: number };
let keyboardVisible: boolean;

beforeEach(() => {
  keyboardListeners = {};
  dimensionListeners = [];
  windowSize = { width: 400, height: 900 }; // portrait
  keyboardVisible = false;

  jest.spyOn(Keyboard, 'isVisible').mockImplementation(() => keyboardVisible);
  jest.spyOn(Keyboard, 'addListener').mockImplementation(((
    event: KeyboardEvent,
    cb: () => void,
  ) => {
    (keyboardListeners[event] ??= []).push(cb);
    return { remove: jest.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any);

  jest.spyOn(Dimensions, 'get').mockImplementation((() => ({
    ...windowSize,
    scale: 3,
    fontScale: 1,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  })) as any);
  jest.spyOn(Dimensions, 'addEventListener').mockImplementation(((
    _type: string,
    cb: (event: { window: { width: number; height: number } }) => void,
  ) => {
    dimensionListeners.push(cb);
    return { remove: jest.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any);
});

afterEach(() => {
  jest.restoreAllMocks();
});

function emitKeyboard(event: KeyboardEvent) {
  act(() => {
    keyboardListeners[event]?.forEach((cb) => cb());
  });
}

function emitResize(width: number, height: number) {
  act(() => {
    dimensionListeners.forEach((cb) => cb({ window: { width, height } }));
  });
}

/** Renders the provider and returns a live handle on the context value. */
function renderProvider(props: { scale?: number; initialSide?: 'left' | 'right' } = {}) {
  const handle: { current: OneHandValue } = { current: null as unknown as OneHandValue };
  function Probe() {
    handle.current = useOneHand();
    return null;
  }
  render(
    <OneHandProvider {...props}>
      <Probe />
    </OneHandProvider>,
  );
  return handle;
}

describe('initial state', () => {
  it('starts inactive, docked right, at the default scale', () => {
    const ctx = renderProvider();
    expect(ctx.current.active).toBe(false);
    expect(ctx.current.side).toBe('right');
    expect(ctx.current.scale).toBe(DEFAULT_ONE_HAND_SCALE);
  });

  it('honors initialSide', () => {
    expect(renderProvider({ initialSide: 'left' }).current.side).toBe('left');
  });

  it('throws when useOneHand is called outside the provider', () => {
    function Orphan() {
      useOneHand();
      return null;
    }
    // React logs the error boundary trace; the assertion is on the throw itself.
    jest.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Orphan />)).toThrow(/must be used inside/);
  });
});

describe('enable / disable / toggleSide', () => {
  it('enable() activates and enable(side) also docks to that side', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable('left'));
    expect(ctx.current.active).toBe(true);
    expect(ctx.current.side).toBe('left');
  });

  it('enable() without a side keeps the current side', () => {
    const ctx = renderProvider({ initialSide: 'left' });
    act(() => ctx.current.enable());
    expect(ctx.current.side).toBe('left');
  });

  it('disable() deactivates', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    act(() => ctx.current.disable());
    expect(ctx.current.active).toBe(false);
  });

  it('toggleSide() flips the side without changing active', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    act(() => ctx.current.toggleSide());
    expect(ctx.current.side).toBe('left');
    expect(ctx.current.active).toBe(true);
  });

  it(
    'keeps a stable enable identity across re-renders — the corner gesture arms a timer ' +
      'that calls it ~450 ms later, so a changing identity would run a stale closure',
    () => {
      const ctx = renderProvider();
      const before = ctx.current.enable;
      emitResize(401, 900);
      expect(ctx.current.enable).toBe(before);
    },
  );
});

describe('keyboard policy', () => {
  it('enable() is a no-op while the keyboard is visible', () => {
    const ctx = renderProvider();
    emitKeyboard('keyboardWillShow');
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(false);
  });

  it('exits automatically when the keyboard opens while docked', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(true);
    emitKeyboard('keyboardWillShow');
    expect(ctx.current.active).toBe(false);
  });

  it('allows enable() again once the keyboard hides', () => {
    const ctx = renderProvider();
    emitKeyboard('keyboardDidShow');
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(false);
    emitKeyboard('keyboardDidHide');
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(true);
  });

  it('starts from the keyboard state at mount, not from an assumed "hidden"', () => {
    keyboardVisible = true;
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(false);
  });
});

describe('orientation policy — the mode is portrait-only', () => {
  it('enable() is a no-op in landscape', () => {
    windowSize = { width: 900, height: 400 };
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(false);
  });

  it('enable() works in portrait', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(true);
  });

  it('exits on rotation to landscape', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    emitResize(900, 400);
    expect(ctx.current.active).toBe(false);
  });

  it(
    'exits on ANY window resize, not just an orientation flip — split-screen and ' +
      'freeform resizes invalidate the native transform the same way',
    () => {
      const ctx = renderProvider();
      act(() => ctx.current.enable());
      emitResize(400, 600); // still portrait, smaller window
      expect(ctx.current.active).toBe(false);
    },
  );

  it('stays active when a resize event repeats the current size', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    emitResize(400, 900);
    expect(ctx.current.active).toBe(true);
  });

  it('can be re-entered after rotating back to portrait', () => {
    const ctx = renderProvider();
    act(() => ctx.current.enable());
    emitResize(900, 400);
    emitResize(400, 900);
    act(() => ctx.current.enable());
    expect(ctx.current.active).toBe(true);
  });
});

describe('scale validation', () => {
  it.each([0.3, 0.5, 0.75, 0.85])('accepts %p', (scale) => {
    expect(renderProvider({ scale }).current.scale).toBe(scale);
  });

  it.each([
    ['below the minimum', 0.1],
    ['above the maximum — the 0.85 cap is what lets the top inset be zeroed', 0.9],
    ['1, which would leave no backdrop to tap and no way out of the mode', 1],
    ['NaN', NaN],
  ])('falls back to the default for %s', (_label, scale) => {
    jest.spyOn(console, 'warn').mockImplementation(() => {});
    expect(renderProvider({ scale }).current.scale).toBe(DEFAULT_ONE_HAND_SCALE);
  });
});
