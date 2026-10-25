// Authored resolver smoke/contract tests on Node, NOT native QuickJS/isolation proof.
// Fixtures are the repository's captured Blender video API documents, not fake media.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../../..');
const kotlin = fs.readFileSync(path.join(root, 'app/src/main/java/com/cinator/app/engines/nuvio/ReviewedEmbeddedScript.kt'), 'utf8');
const source = kotlin.match(/private const val SOURCE = """([\s\S]*?)"""/)[1];
const invocation = kotlin.match(/const val INVOCATION = "([^"]+)"/)[1];
const fixtureDir = path.join(root, 'app/src/test/resources/blender');
const ids = [
  ['bf1f3fb5-b119-4f9f-9930-8e20e892b898', 720],
  ['0eb052d0-fd51-43e6-aa33-ecdbf77a5d40', 480],
  ['8533ea43-4271-4a57-9694-e9d0b35e1aa1', 480],
  ['f507dfdc-e73e-45a4-9778-d758cbe1ce96', 480]
];
function fixture(id = ids[0][0]) { return JSON.parse(fs.readFileSync(path.join(fixtureDir, id + '.json'), 'utf8')); }
function resolve(document, uuid = ids[0][0], height = 720) {
  const context = vm.createContext({ __parentDocument: typeof document === 'string' ? document : JSON.stringify(document), __expectedUuid: uuid, __preferredHeight: height });
  vm.runInContext(source, context, { timeout: 1000 });
  const raw = vm.runInContext(invocation, context, { timeout: 1000 });
  assert.equal(typeof raw, 'string');
  return JSON.parse(raw);
}

test('exact constant bytes have the hardcoded reviewed SHA-256', () => {
  const pin = kotlin.match(/const val BLENDER_RESOLVER_SHA256 = "([0-9a-f]{64})"/)[1];
  assert.equal(crypto.createHash('sha256').update(source, 'utf8').digest('hex'), pin);
});

test('all four real API captures return exact-height listed file metadata only', () => {
  for (const [uuid, height] of ids) {
    const doc = fixture(uuid);
    const files = [...(doc.files || []), ...(doc.streamingPlaylists || []).flatMap(p => p.files || [])];
    const file = files.find(f => f.height === height);
    assert.ok(file, 'capture must contain the expected real file');
    assert.deepEqual(resolve(doc, uuid, height), [{ uuid, height, url: file.fileUrl, size: file.size }]);
  }
});

test('nested direct files are accepted and duplicate URLs yield one candidate', () => {
  const doc = fixture();
  const expected = resolve(doc);
  doc.streamingPlaylists = [{ files: doc.files }, { files: doc.files }];
  assert.deepEqual(resolve(doc), expected);
  doc.files = [];
  assert.deepEqual(resolve(doc), expected);
});

test('wrong identity, malformed data and unavailable height have no fallback', () => {
  const doc = fixture();
  assert.deepEqual(resolve(doc, ids[1][0]), []);
  assert.deepEqual(resolve(doc, ids[0][0], 2160), []);
  for (const raw of ['{}', '[]', 'null', '{', '{} true', ' '.repeat(131073)]) assert.deepEqual(resolve(raw), []);
  for (const height of [null, '720', 720.5, 0, 2161]) assert.deepEqual(resolve(doc, ids[0][0], height), []);
  for (const uuid of ['', ids[0][0].toUpperCase(), '../' + ids[0][0]]) assert.deepEqual(resolve(doc, uuid), []);
  for (const change of [{ isLive: true }, { isLocal: false }, { url: doc.url + '?x' }]) assert.deepEqual(resolve({ ...doc, ...change }), []);
});

test('matching files require exact URL, numeric resolution and audio/video', () => {
  const original = fixture();
  const file = original.files.find(f => f.height === 720);
  const changes = [
    { fileUrl: file.fileUrl + '?token=x' }, { fileUrl: file.fileDownloadUrl },
    { fileUrl: file.torrentUrl }, { fileUrl: file.fileUrl.replace('video.blender.org', 'elsewhere.example') },
    { height: '720' }, { resolution: { id: '720' } }, { resolution: null },
    { size: 0 }, { size: 1.5 }, { size: Number.MAX_SAFE_INTEGER + 1 },
    { hasAudio: false }, { hasVideo: false }, { hasAudio: 'true' }
  ];
  for (const change of changes) assert.deepEqual(resolve({ ...original, files: [{ ...file, ...change }] }), []);
  assert.deepEqual(resolve({ ...original, files: [], streamingPlaylists: [{ files: [], playlistUrl: file.fileUrl }] }), []);
});

test('bounded file and playlist traversal rejects oversized and malformed collections', () => {
  const doc = fixture();
  // Retain real captured candidate metadata, dropping unrelated torrent/download fields
  // so the 256-row boundary is exercised inside the separate document-size budget.
  const captured = doc.files[0];
  const file = { height: captured.height, resolution: captured.resolution, size: captured.size,
    fileUrl: captured.fileUrl, hasAudio: captured.hasAudio, hasVideo: captured.hasVideo };
  for (const change of [
    { files: {} }, { files: [null, false, 4] }, { files: Array(257).fill(file) },
    { streamingPlaylists: {} }, { streamingPlaylists: Array(9).fill({ files: [] }) },
    { streamingPlaylists: [null] }, { streamingPlaylists: [{ files: {} }] },
    { streamingPlaylists: [{ files: Array(256).fill(file) }] }
  ]) assert.deepEqual(resolve({ ...doc, ...change }), []);
  assert.equal(resolve({ ...doc, files: Array(256).fill(file) }).length, 1);
});
