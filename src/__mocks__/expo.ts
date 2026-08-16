/**
 * Stand-in for the `expo` peer dependency, which is deliberately not installed here (see
 * jest.config.js). Returns "no native module compiled in" by default — the state a consumer
 * running in Expo Go sees. Tests that need a module present mock it at a narrower seam
 * (`jest.doMock('expo', …)` inside `jest.isolateModules`), because the real
 * `native/OneHandWindows` reads it once at import time.
 */
export function requireOptionalNativeModule<T>(_name: string): T | null {
  return null;
}
