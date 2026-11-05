/** Read dimensions before allocating a native decoder bitmap, including common WebP variants. */
export function thumbnailDimensions(
  bytes: Buffer,
): {width: number; height: number} | undefined {
  if (
    bytes.length >= 24 &&
    bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  )
    return {width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20)}
  if (bytes.length >= 4 && bytes.readUInt16BE(0) === 0xffd8) {
    let offset = 2
    while (offset + 3 < bytes.length) {
      if (bytes[offset++] !== 0xff) return
      while (bytes[offset] === 0xff) offset++
      const marker = bytes[offset++]
      if (marker === 0xda || marker === 0xd9) return
      if (
        marker === 0x01 ||
        marker === 0xd8 ||
        (marker >= 0xd0 && marker <= 0xd7)
      )
        continue
      if (offset + 2 > bytes.length) return
      const length = bytes.readUInt16BE(offset)
      if (length < 2 || offset + length > bytes.length) return
      if (
        [
          0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd,
          0xce, 0xcf,
        ].includes(marker)
      ) {
        if (length < 8) return
        return {
          width: bytes.readUInt16BE(offset + 5),
          height: bytes.readUInt16BE(offset + 3),
        }
      }
      offset += length
    }
  }
  if (
    bytes.length >= 30 &&
    bytes.toString('ascii', 0, 4) === 'RIFF' &&
    bytes.toString('ascii', 8, 12) === 'WEBP'
  ) {
    const type = bytes.toString('ascii', 12, 16)
    if (type === 'VP8X')
      return {
        width: bytes.readUIntLE(24, 3) + 1,
        height: bytes.readUIntLE(27, 3) + 1,
      }
    if (type === 'VP8L' && bytes[20] === 0x2f) {
      const size = bytes.readUInt32LE(21)
      return {width: (size & 0x3fff) + 1, height: ((size >>> 14) & 0x3fff) + 1}
    }
    if (
      type === 'VP8 ' &&
      bytes[23] === 0x9d &&
      bytes[24] === 1 &&
      bytes[25] === 0x2a
    )
      return {
        width: bytes.readUInt16LE(26) & 0x3fff,
        height: bytes.readUInt16LE(28) & 0x3fff,
      }
  }
}

export function boundedThumbnail(bytes: Buffer) {
  const size = thumbnailDimensions(bytes)
  return (
    !!size &&
    size.width > 0 &&
    size.height > 0 &&
    size.width <= 8192 &&
    size.height <= 8192 &&
    size.width * size.height <= 16_000_000
  )
}
