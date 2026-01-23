export type ItemMeta = {
  itemId: number
  name: string
  priceMinor: number
  currency: string
}

const cache = new Map<number, ItemMeta>()

export function updateItemCache(items: ItemMeta[]) {
  items.forEach((item) => {
    cache.set(item.itemId, item)
  })
}

export function getItemMeta(itemId: number): ItemMeta | undefined {
  return cache.get(itemId)
}
