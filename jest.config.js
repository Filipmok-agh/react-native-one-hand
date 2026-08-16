module.exports = {
  preset: '@react-native/jest-preset',
  roots: ['<rootDir>/src'],
  moduleNameMapper: {
    // `expo` and `react-native-safe-area-context` are PEER dependencies, deliberately not
    // installed here (the latter is optional even for consumers). The tests supply their
    // own stand-ins so the unit suite runs with just react + react-native.
    '^expo$': '<rootDir>/src/__mocks__/expo.ts',
    '^react-native-safe-area-context$':
      '<rootDir>/src/__mocks__/react-native-safe-area-context.tsx',
  },
  collectCoverageFrom: ['src/**/*.{ts,tsx}', '!src/__tests__/**', '!src/__mocks__/**'],
};
