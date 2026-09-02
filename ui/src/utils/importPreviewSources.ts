export type PreviewFormat = 'json' | 'lottie' | 'tgs'

export type ImportPreviewCandidate = {
  sourceFileName: string
  sha256: string
}

export type ImportPreviewSource = {
  src: string
  format: PreviewFormat
}

type ArchiveEntry = {
  name: string
  format: PreviewFormat
  bytes: Uint8Array
}

const ZIP_END_OF_CENTRAL_DIRECTORY = 0x06054b50
const ZIP_CENTRAL_DIRECTORY_FILE = 0x02014b50
const ZIP_LOCAL_FILE = 0x04034b50
const MAX_PREVIEW_BYTES = 200 * 1024 * 1024

export function importPreviewKey(candidate: ImportPreviewCandidate): string {
  return `${candidate.sourceFileName}-${candidate.sha256}`
}

export function previewDimensions(width: number | undefined, height: number | undefined, maximumSize: number) {
  const safeWidth = clampDimension(width)
  const safeHeight = clampDimension(height)
  const scale = maximumSize / Math.max(safeWidth, safeHeight)
  return {
    width: Math.max(1, Math.round(safeWidth * scale)),
    height: Math.max(1, Math.round(safeHeight * scale)),
  }
}

export async function resolveImportPreviewSources(
  files: File[],
  candidates: ImportPreviewCandidate[],
): Promise<{ sources: Record<string, ImportPreviewSource>; revoke: () => void }> {
  const sourcesByFilename = new Map<string, ImportPreviewSource>()
  const objectUrls: string[] = []

  const addSource = (filename: string, format: PreviewFormat, blob: Blob) => {
    const key = normalizedFilename(filename)
    if (sourcesByFilename.has(key)) return
    const src = URL.createObjectURL(blob)
    objectUrls.push(src)
    sourcesByFilename.set(key, { src, format })
  }

  for (const file of files) {
    const format = previewFormat(file.name)
    if (format) {
      addSource(file.name, format, file)
      continue
    }
    if (!file.name.toLocaleLowerCase().endsWith('.zip')) continue
    try {
      const entries = await readAnimationArchive(file)
      for (const entry of entries) {
        addSource(entry.name, entry.format, new Blob([copyToArrayBuffer(entry.bytes)], { type: mediaType(entry.format) }))
      }
    } catch {
      // The server remains the source of truth for archive validation. A
      // browser that cannot read an archive simply leaves its preview blank.
    }
  }

  const sources: Record<string, ImportPreviewSource> = {}
  for (const candidate of candidates) {
    const source = sourcesByFilename.get(normalizedFilename(candidate.sourceFileName))
    if (source) sources[importPreviewKey(candidate)] = source
  }

  return {
    sources,
    revoke: () => objectUrls.forEach((url) => URL.revokeObjectURL(url)),
  }
}

function clampDimension(value: number | undefined): number {
  const dimension = Number(value)
  return Number.isFinite(dimension) && dimension > 0 && dimension <= 4096 ? dimension : 160
}

function previewFormat(filename: string): PreviewFormat | null {
  const name = filename.toLocaleLowerCase()
  if (name.endsWith('.json')) return 'json'
  if (name.endsWith('.lottie')) return 'lottie'
  if (name.endsWith('.tgs')) return 'tgs'
  return null
}

function mediaType(format: PreviewFormat): string {
  if (format === 'json') return 'application/json'
  if (format === 'tgs') return 'application/gzip'
  return 'application/zip'
}

function normalizedFilename(filename: string): string {
  return filename.replace(/\\/g, '/')
}

function copyToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

async function readAnimationArchive(file: File): Promise<ArchiveEntry[]> {
  const archive = await file.arrayBuffer()
  const view = new DataView(archive)
  const endOffset = findEndOfCentralDirectory(view)
  if (endOffset < 0 || view.getUint16(endOffset + 4, true) !== 0 || view.getUint16(endOffset + 6, true) !== 0) {
    throw new Error('Unsupported ZIP archive')
  }

  const entryCount = view.getUint16(endOffset + 10, true)
  let offset = view.getUint32(endOffset + 16, true)
  const entries: ArchiveEntry[] = []
  for (let index = 0; index < entryCount; index += 1) {
    if (offset + 46 > view.byteLength || view.getUint32(offset, true) !== ZIP_CENTRAL_DIRECTORY_FILE) {
      throw new Error('Invalid ZIP central directory')
    }
    const flags = view.getUint16(offset + 8, true)
    const compressionMethod = view.getUint16(offset + 10, true)
    const compressedSize = view.getUint32(offset + 20, true)
    const uncompressedSize = view.getUint32(offset + 24, true)
    const filenameLength = view.getUint16(offset + 28, true)
    const extraLength = view.getUint16(offset + 30, true)
    const commentLength = view.getUint16(offset + 32, true)
    const localHeaderOffset = view.getUint32(offset + 42, true)
    const filenameOffset = offset + 46
    const nextOffset = filenameOffset + filenameLength + extraLength + commentLength
    if (nextOffset > view.byteLength) throw new Error('Invalid ZIP file entry')
    const filename = new TextDecoder('utf-8').decode(new Uint8Array(archive, filenameOffset, filenameLength))
    const format = previewFormat(filename)
    if (format && !(flags & 0x1) && uncompressedSize <= MAX_PREVIEW_BYTES) {
      const bytes = await extractEntry(archive, view, localHeaderOffset, compressedSize, compressionMethod)
      if (bytes.byteLength <= MAX_PREVIEW_BYTES) entries.push({ name: filename, format, bytes })
    }
    offset = nextOffset
  }
  return entries
}

function findEndOfCentralDirectory(view: DataView): number {
  const earliestOffset = Math.max(0, view.byteLength - 65_557)
  for (let offset = view.byteLength - 22; offset >= earliestOffset; offset -= 1) {
    if (view.getUint32(offset, true) === ZIP_END_OF_CENTRAL_DIRECTORY) return offset
  }
  return -1
}

async function extractEntry(
  archive: ArrayBuffer,
  view: DataView,
  localHeaderOffset: number,
  compressedSize: number,
  compressionMethod: number,
): Promise<Uint8Array> {
  if (localHeaderOffset + 30 > view.byteLength || view.getUint32(localHeaderOffset, true) !== ZIP_LOCAL_FILE) {
    throw new Error('Invalid ZIP local file header')
  }
  const filenameLength = view.getUint16(localHeaderOffset + 26, true)
  const extraLength = view.getUint16(localHeaderOffset + 28, true)
  const contentOffset = localHeaderOffset + 30 + filenameLength + extraLength
  const contentEnd = contentOffset + compressedSize
  if (contentEnd > view.byteLength) throw new Error('Invalid ZIP file payload')
  const compressed = new Uint8Array(archive.slice(contentOffset, contentEnd))
  if (compressionMethod === 0) return compressed
  if (compressionMethod !== 8 || typeof DecompressionStream === 'undefined') {
    throw new Error('Unsupported ZIP compression')
  }
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}
