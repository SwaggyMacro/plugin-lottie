export interface LottieDimensions {
  width: number
  height: number
}

interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  uncompressedSize: number
  localHeaderOffset: number
}

const clamp = (value: unknown, fallback = 160): number => {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 && parsed <= 4096 ? Math.round(parsed) : fallback
}

const fromJson = (value: unknown): LottieDimensions | null => {
  if (!value || typeof value !== 'object') return null
  const record = value as { w?: unknown; h?: unknown }
  if (record.w == null || record.h == null) return null
  return { width: clamp(record.w), height: clamp(record.h) }
}

const readUint16 = (view: DataView, offset: number): number => view.getUint16(offset, true)
const readUint32 = (view: DataView, offset: number): number => view.getUint32(offset, true)

function findEndOfCentralDirectory(bytes: Uint8Array): number {
  const minimum = Math.max(0, bytes.length - 0xffff - 22)
  for (let offset = bytes.length - 22; offset >= minimum; offset -= 1) {
    if (readUint32(new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), offset) === 0x06054b50) return offset
  }
  return -1
}

function readCentralDirectory(bytes: Uint8Array): ZipEntry[] | null {
  try {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    const eocd = findEndOfCentralDirectory(bytes)
    if (eocd < 0) return null
    const count = readUint16(view, eocd + 10)
    const size = readUint32(view, eocd + 12)
    const offset = readUint32(view, eocd + 16)
    if (offset + size > bytes.byteLength || count > 10000) return null
    const decoder = new TextDecoder()
    const entries: ZipEntry[] = []
    let cursor = offset
    for (let index = 0; index < count; index += 1) {
      if (cursor + 46 > bytes.byteLength || readUint32(view, cursor) !== 0x02014b50) return null
      const nameLength = readUint16(view, cursor + 28)
      const extraLength = readUint16(view, cursor + 30)
      const commentLength = readUint16(view, cursor + 32)
      const end = cursor + 46 + nameLength + extraLength + commentLength
      if (end > bytes.byteLength) return null
      entries.push({
        name: decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength)).replaceAll('\\', '/'),
        method: readUint16(view, cursor + 10),
        compressedSize: readUint32(view, cursor + 20),
        uncompressedSize: readUint32(view, cursor + 24),
        localHeaderOffset: readUint32(view, cursor + 42),
      })
      cursor = end
    }
    return entries
  } catch {
    return null
  }
}

async function unzipEntry(bytes: Uint8Array, entry: ZipEntry): Promise<Uint8Array | null> {
  try {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    const offset = entry.localHeaderOffset
    if (offset + 30 > bytes.byteLength || readUint32(view, offset) !== 0x04034b50) return null
    const nameLength = readUint16(view, offset + 26)
    const extraLength = readUint16(view, offset + 28)
    const start = offset + 30 + nameLength + extraLength
    const end = start + entry.compressedSize
    if (start < 0 || end > bytes.byteLength || entry.uncompressedSize > 16 * 1024 * 1024) return null
    const payload = bytes.slice(start, end)
    if (entry.method === 0) return payload
    if (entry.method !== 8 || typeof DecompressionStream === 'undefined') return null
    const stream = new Blob([payload]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
    const result = new Uint8Array(await new Response(stream).arrayBuffer())
    return result.length === entry.uncompressedSize ? result : null
  } catch {
    return null
  }
}

function manifestAnimationName(manifest: unknown): string | null {
  if (!manifest || typeof manifest !== 'object') return null
  const record = manifest as { animations?: unknown; animation?: unknown }
  const list = Array.isArray(record.animations) ? record.animations : record.animation ? [record.animation] : []
  const first = list[0]
  if (typeof first === 'string') return first
  if (!first || typeof first !== 'object') return null
  const item = first as Record<string, unknown>
  for (const key of ['filename', 'path', 'url', 'src']) {
    if (typeof item[key] === 'string' && item[key]) return item[key] as string
  }
  return null
}

async function readDotLottieDimensions(bytes: Uint8Array): Promise<LottieDimensions | null> {
  const entries = readCentralDirectory(bytes)
  if (!entries?.length) return null
  const manifestEntry = entries.find((entry) => entry.name.toLowerCase() === 'manifest.json')
  if (!manifestEntry) return null
  const manifestBytes = await unzipEntry(bytes, manifestEntry)
  if (!manifestBytes) return null
  let manifest: unknown
  try { manifest = JSON.parse(new TextDecoder().decode(manifestBytes)) } catch { return null }
  const requested = manifestAnimationName(manifest)?.replace(/^\/+/, '').replaceAll('\\', '/')
  const animationEntry = (requested && !requested.includes('..')
    ? entries.find((entry) => entry.name === requested)
    : undefined) || entries.find((entry) => entry.name.toLowerCase().endsWith('.json') && entry.name.toLowerCase() !== 'manifest.json')
  if (!animationEntry) return null
  const animationBytes = await unzipEntry(bytes, animationEntry)
  if (!animationBytes) return null
  try { return fromJson(JSON.parse(new TextDecoder().decode(animationBytes))) } catch { return null }
}

/** Reads intrinsic dimensions without persisting attachment bytes in plugin metadata. */
export async function readLottieDimensions(url: string | null, format: string): Promise<LottieDimensions | null> {
  const normalizedFormat = format?.toLowerCase()
  if (!url || !['json', 'tgs', 'lottie'].includes(normalizedFormat)) return null
  try {
    const response = await fetch(url, { credentials: 'include' })
    if (!response.ok) return null
    if (normalizedFormat === 'json') return fromJson(await response.json())
    if (normalizedFormat === 'lottie') return readDotLottieDimensions(new Uint8Array(await response.arrayBuffer()))
    if (typeof DecompressionStream === 'undefined') return null
    const stream = response.body?.pipeThrough(new DecompressionStream('gzip'))
    if (!stream) return null
    const text = await new Response(stream).text()
    return fromJson(JSON.parse(text))
  } catch {
    return null
  }
}
