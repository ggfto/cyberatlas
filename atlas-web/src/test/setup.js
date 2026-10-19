import '@testing-library/jest-dom/vitest'

// O React Flow mede o canvas com ResizeObserver, que o jsdom nao tem.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver || ResizeObserverStub
