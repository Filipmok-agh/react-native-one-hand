import { useContext } from 'react';
import { render } from '@testing-library/react-native';
import { SafeAreaInsetsContext, type EdgeInsets } from 'react-native-safe-area-context';
import { DockedInsets } from '../DockedInsets';

const REAL_INSETS: EdgeInsets = { top: 59, right: 0, bottom: 34, left: 0 };

/** Reads the insets a consumer below DockedInsets would see. */
function renderWithInsets(active: boolean, provided: EdgeInsets | null) {
  const seen: { current: EdgeInsets | null | undefined } = { current: undefined };
  function Consumer() {
    seen.current = useContext(SafeAreaInsetsContext);
    return null;
  }
  render(
    <SafeAreaInsetsContext.Provider value={provided}>
      <DockedInsets active={active}>
        <Consumer />
      </DockedInsets>
    </SafeAreaInsetsContext.Provider>,
  );
  return seen;
}

describe('while docked', () => {
  it(
    'zeroes the top inset — docked, the app hangs from the bottom and its top edge is ' +
      'guaranteed to clear the cutout, so reserving space for it is wasted',
    () => {
      expect(renderWithInsets(true, REAL_INSETS).current?.top).toBe(0);
    },
  );

  it(
    "KEEPS the bottom inset — the docked app's bottom edge coincides with the physical " +
      'screen bottom, so the home indicator really does overlap it',
    () => {
      expect(renderWithInsets(true, REAL_INSETS).current?.bottom).toBe(34);
    },
  );

  it('leaves left/right untouched', () => {
    const seen = renderWithInsets(true, { top: 59, right: 12, bottom: 34, left: 8 });
    expect(seen.current).toMatchObject({ left: 8, right: 12 });
  });
});

describe('while not docked', () => {
  it('passes the insets through unchanged, by identity', () => {
    expect(renderWithInsets(false, REAL_INSETS).current).toBe(REAL_INSETS);
  });
});

describe('without a SafeAreaProvider above', () => {
  it('renders children unchanged and does not invent insets', () => {
    expect(renderWithInsets(true, null).current).toBeNull();
  });
});

describe('when react-native-safe-area-context is not installed', () => {
  it('renders children unchanged — the package is an OPTIONAL peer dependency', () => {
    jest.isolateModules(() => {
      jest.doMock('react-native-safe-area-context', () => {
        throw new Error("Cannot find module 'react-native-safe-area-context'");
      });
      const { DockedInsets: Isolated } = require('../DockedInsets');
      const { toJSON } = render(<Isolated active={true}>{null}</Isolated>);
      expect(toJSON()).toBeNull();
    });
  });
});
