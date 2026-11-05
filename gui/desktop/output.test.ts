import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import {safeName, platformFolder} from '../shared/output.js'
import {DownloadManager} from './manager.js'
import {FakeEngine} from './fake-engine.js'
test('readable names preserve Arabic, neutralize output templates and Windows reserved names', () => {
  assert.equal(safeName('درس عربي'), 'درس عربي')
  assert.equal(safeName('CON'), '_CON')
  assert.equal(safeName('../../%(title)s'), '.._..__(title)s')
  assert.equal(platformFolder('https://m.youtube.com/watch?v=x'), 'YouTube')
  assert.equal(
    platformFolder('https://youtube.com.evil.test/video'),
    'youtube.com.evil.test',
  )
})
test('flat organization preserves existing files and persists the preference', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-output-'))
  const store = path.join(dir, 'queue.json'),
    output = path.join(dir, 'downloads')
  await fs.mkdir(output)
  await fs.writeFile(path.join(output, 'A video.mp4'), 'existing file')
  const engine = new FakeEngine()
  const manager = new DownloadManager({store, defaultOutDir: output, engine})
  try {
    await manager.init()
    await manager.updateSettings({organization: 'flat'})
    await manager.inspect('https://youtube.com/watch?v=x')
    await manager.enqueue('https://youtube.com/watch?v=x', {
      type: 'video',
      codec: 'auto',
      container: 'mp4',
    })
    for (
      let i = 0;
      i < 100 && manager.snapshot().jobs[0].status !== 'completed';
      i++
    )
      await new Promise((r) => setTimeout(r, 10))
    assert.equal(
      manager.snapshot().jobs[0].file,
      path.join(output, 'A video (2).mp4'),
    )
    assert.equal(
      await fs.readFile(path.join(output, 'A video.mp4'), 'utf8'),
      'existing file',
    )
    await manager.close()
    const restored = new DownloadManager({store, defaultOutDir: output, engine})
    await restored.init()
    assert.equal(restored.snapshot().settings.organization, 'flat')
    await restored.close()
  } finally {
    await manager.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})
