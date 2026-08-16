import { Platform } from 'react-native';

/**
 * `native/OneHandWindows` resolves the native module and the runtime check ONCE at import
 * time, so every case needs a fresh module registry.
 */
function loadWith({
  os,
  version,
  hasModule,
}: {
  os: 'ios' | 'android';
  version: number | string;
  hasModule: boolean;
}) {
  let result!: typeof import('../native/OneHandWindows');
  jest.isolateModules(() => {
    Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
    Object.defineProperty(Platform, 'Version', { value: version, configurable: true });
    jest.doMock('expo', () => ({
      requireOptionalNativeModule: () =>
        hasModule ? { addListener: jest.fn(), setScale: jest.fn(), reset: jest.fn() } : null,
    }));
    result = require('../native/OneHandWindows');
  });
  return result;
}

const REAL_OS = Platform.OS;
const REAL_VERSION = Platform.Version;

afterEach(() => {
  Object.defineProperty(Platform, 'OS', { value: REAL_OS, configurable: true });
  Object.defineProperty(Platform, 'Version', { value: REAL_VERSION, configurable: true });
});

describe('one-hand availability', () => {
  it('is available on iOS when the native module is compiled in', () => {
    const mod = loadWith({ os: 'ios', version: '26.0', hasModule: true });
    expect(mod.isOneHandWindowsAvailable).toBe(true);
    expect(mod.oneHandUnavailabilityReason).toBeNull();
  });

  it('reports missing-module without a native build (e.g. Expo Go)', () => {
    const mod = loadWith({ os: 'ios', version: '26.0', hasModule: false });
    expect(mod.isOneHandWindowsAvailable).toBe(false);
    expect(mod.oneHandUnavailabilityReason).toBe('missing-module');
  });

  it(
    'reports unsupported-os on Android below API 29 — the module compiles in but is ' +
      'INERT there, so module presence alone would lie',
    () => {
      const mod = loadWith({ os: 'android', version: 28, hasModule: true });
      expect(mod.isOneHandWindowsAvailable).toBe(false);
      expect(mod.oneHandUnavailabilityReason).toBe('unsupported-os');
    },
  );

  it('is available on Android from API 29', () => {
    expect(
      loadWith({ os: 'android', version: 29, hasModule: true }).isOneHandWindowsAvailable,
    ).toBe(true);
  });

  it('prefers missing-module over unsupported-os when both apply', () => {
    expect(
      loadWith({ os: 'android', version: 28, hasModule: false }).oneHandUnavailabilityReason,
    ).toBe('missing-module');
  });
});

describe('native calls', () => {
  it(
    'swallows a rejection from a call racing module teardown, rather than surfacing an ' +
      'unhandled rejection in the consumer app',
    async () => {
      let mod!: typeof import('../native/OneHandWindows');
      jest.isolateModules(() => {
        jest.doMock('expo', () => ({
          requireOptionalNativeModule: () => ({
            addListener: jest.fn(),
            setScale: () => Promise.reject(new Error('module gone')),
            reset: () => Promise.reject(new Error('module gone')),
          }),
        }));
        mod = require('../native/OneHandWindows');
      });
      expect(() => mod.setWindowsScale(0.75, 'right', 'hint', null, null)).not.toThrow();
      expect(() => mod.resetWindowsScale()).not.toThrow();
      // Let the rejected promises settle; an unswallowed one would fail the run.
      await new Promise((resolve) => setImmediate(resolve));
    },
  );

  it('is a no-op when the native module is absent', () => {
    const mod = loadWith({ os: 'ios', version: '26.0', hasModule: false });
    expect(() => mod.setWindowsScale(0.75, 'right', 'hint', null, null)).not.toThrow();
    expect(mod.addDismissListener(jest.fn())).toBeNull();
  });
});
