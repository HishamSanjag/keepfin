import assert from 'node:assert/strict'
import {describe, it} from 'node:test'
import {CloudHttp} from './http.js'
import type {CloudRequest} from '../../shared/core/cloud.js'

function counting() {
  let open = 0
  let most = 0
  const fetch = (async () => {
    open++
    most = Math.max(most, open)
    await new Promise((r) => setTimeout(r, 15))
    open--
    return new Response('{}', {status: 200})
  }) as unknown as typeof globalThis.fetch
  return {fetch, most: () => most}
}

const account = {id: 'a', provider: 'dropbox' as const, authorization: async () => null, refresh: async () => false}
const req = (serial: boolean): CloudRequest => ({method: 'POST', url: 'https://api.dropboxapi.com/2/files/delete_v2', headers: {}, auth: false, ...(serial ? {serial} : {})})

describe('CloudHttp serial requests', () => {
  it('sends serial requests one at a time', async () => {
    const f = counting()
    const http = new CloudHttp(account, {fetch: f.fetch})
    await Promise.all([1, 2, 3, 4].map(() => http.send(req(true))))
    assert.equal(f.most(), 1)
  })

  it('sends other requests side by side', async () => {
    const f = counting()
    const http = new CloudHttp(account, {fetch: f.fetch})
    await Promise.all([1, 2, 3, 4].map(() => http.send(req(false))))
    assert.ok(f.most() > 1)
  })
})
