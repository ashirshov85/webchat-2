import { vi } from 'vitest'

interface LocationImpl {
  assign: (url: string) => void
}

const implSymbol = Reflect.ownKeys(window.location).find(
  (key): key is symbol => typeof key === 'symbol',
)

export function stubLocationAssign() {
  if (implSymbol === undefined) {
    throw new Error('jsdom location implementation symbol is unavailable')
  }
  const impl = (window.location as unknown as Record<symbol, LocationImpl>)[implSymbol]
  const prototype = Object.getPrototypeOf(impl) as LocationImpl | null
  if (prototype === null) {
    throw new Error('jsdom location implementation prototype is unavailable')
  }
  return vi.spyOn(prototype, 'assign').mockImplementation(() => {})
}
