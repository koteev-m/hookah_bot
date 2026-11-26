// Read the committed browser database independently of the application's storage code.
async function visitRecord(page, key) {
  return page.evaluate(key => new Promise((resolve, reject) => {
    const request = indexedDB.open('hookah-guest-visits', 1)
    request.onupgradeneeded = () => request.transaction.abort()
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result
      const tx = db.transaction('visits', 'readonly')
      const read = tx.objectStore('visits').get(key)
      tx.oncomplete = () => { db.close(); resolve(read.result ?? null) }
      tx.onabort = () => { db.close(); reject(tx.error) }
    }
  }), key)
}

async function storedPointer(page, key) {
  const record = await visitRecord(page, key)
  return record?.pointer ? JSON.stringify(record.pointer) : null
}

module.exports = { visitRecord, storedPointer }

async function storedCart(page, key) {
  const record = await visitRecord(page, key)
  if (!record) return page.evaluate(k => localStorage.getItem(k), key)
  if (record.ended || (!record.draft.items.length && !record.draft.commentDraft)) return null
  return JSON.stringify(record.draft)
}

async function seedCart(page, key, json) {
  await page.evaluate(([key, json]) => new Promise((resolve, reject) => {
    const open = indexedDB.open('hookah-guest-visits', 1)
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const db = open.result
      const tx = db.transaction('visits', 'readwrite')
      tx.objectStore('visits').put({ draft: JSON.parse(json) }, key)
      tx.oncomplete = () => { db.close(); resolve() }
      tx.onabort = () => { db.close(); reject(tx.error) }
    }
  }), [key, json])
}

module.exports.storedCart = storedCart
module.exports.seedCart = seedCart
