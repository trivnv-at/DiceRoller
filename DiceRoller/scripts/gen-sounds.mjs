#!/usr/bin/env node
/**
 * Generate efek suara procedural (WAV) untuk Android — tanpa aset eksternal.
 *
 * Menulis ke app/src/main/res/raw/: dice_roll, token_hop, token_land, ladder,
 * snake, win. 16-bit mono 44.1kHz, durasi pendek (< 500ms) agar sinkron dgn
 * animasi & tidak saling tumpang-tindih.
 *
 * Pakai: node scripts/gen-sounds.mjs  (dari folder DiceRoller)
 */

import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const RATE = 44100
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'app', 'src', 'main', 'res', 'raw')

/** Render buffer 16-bit mono dari array samples [-1,1]. */
function render(samples) {
  const n = samples.length
  const data = Buffer.alloc(n * 2)
  for (let i = 0; i < n; i++) {
    let s = Math.max(-1, Math.min(1, samples[i]))
    data.writeInt16LE(Math.round(s * 32767), i * 2)
  }
  // Header WAV (44 byte).
  const header = Buffer.alloc(44)
  header.write('RIFF', 0)
  header.writeUInt32LE(36 + data.length, 4)
  header.write('WAVE', 8)
  header.write('fmt ', 12)
  header.writeUInt32LE(16, 16) // fmt chunk size
  header.writeUInt16LE(1, 20) // PCM
  header.writeUInt16LE(1, 22) // mono
  header.writeUInt32LE(RATE, 24)
  header.writeUInt32LE(RATE * 2, 28) // byte rate
  header.writeUInt16LE(2, 32) // block align
  header.writeUInt16LE(16, 34) // bits per sample
  header.write('data', 36)
  header.writeUInt32LE(data.length, 40)
  return Buffer.concat([header, data])
}

function write(name, samples) {
  const file = join(OUT, `${name}.wav`)
  writeFileSync(file, render(samples))
  console.log('wrote', file, `(${samples.length} samples, ${(samples.length / RATE * 1000).toFixed(0)}ms)`)
}

/** Nada dengan ADSR sederhana: serangan cepat, decay eksponensial. */
function tone({ dur, f0, f1 = f0, amp = 0.5, type = 'sine', attack = 0.005 }) {
  const n = Math.round(dur * RATE)
  const out = new Float64Array(n)
  const freqStep = f1 / f0 // exponential glide factor per-sample is applied via ratio
  for (let i = 0; i < n; i++) {
    const t = i / RATE
    const freq = f0 * Math.pow(f1 / f0, t / dur)
    let v
    if (type === 'sine') v = Math.sin(2 * Math.PI * freq * t)
    else if (type === 'square') v = Math.sign(Math.sin(2 * Math.PI * freq * t))
    else if (type === 'triangle') v = 2 * Math.abs(2 * ((freq * t) % 1) - 1) - 1
    else v = Math.sin(2 * Math.PI * freq * t)
    // envelope: attack linear singkat, lalu decay eksponensial.
    const env = i < attack * RATE ? i / (attack * RATE) : Math.exp(-3.5 * (t - attack) / dur)
    out[i] = v * amp * env
  }
  void freqStep
  return out
}

/** Campur beberapa nada (untuk arpeggio / deret tick). */
function mix(layers) {
  const n = Math.max(...layers.map((l) => l.length))
  const out = new Float64Array(n)
  for (const l of layers) for (let i = 0; i < l.length; i++) out[i] += l[i]
  return out
}

mkdirSync(OUT, { recursive: true })

// dadu berputar: deret "tik" pith naik (~600ms).
{
  const n = Math.round(0.6 * RATE)
  const out = new Float64Array(n)
  for (let i = 0; i < 6; i++) {
    const start = Math.round(i * 0.095 * RATE)
    const t = tone({ dur: 0.05, f0: 700 + i * 90, type: 'triangle', amp: 0.4 })
    for (let k = 0; k < t.length && start + k < n; k++) out[start + k] += t[k]
  }
  write('dice_roll', out)
}

// hop token per kotak: tik pendek.
write('token_hop', tone({ dur: 0.06, f0: 760, type: 'triangle', amp: 0.45 }))

// landing token: "thud" rendah menurun.
write('token_land', tone({ dur: 0.12, f0: 320, f1: 150, amp: 0.6 }))

// naik tangga: glide naik.
write('ladder', tone({ dur: 0.4, f0: 300, f1: 880, type: 'sawtooth', amp: 0.35 }))

// turun ular: glide turun.
write('snake', tone({ dur: 0.42, f0: 720, f1: 180, type: 'sawtooth', amp: 0.35 }))

// menang: arpeggio C5-E5-G5-C6.
write(
  'win',
  mix([
    tone({ dur: 0.18, f0: 523, amp: 0.5 }),
    tone({ dur: 0.18, f0: 659, amp: 0.5, attack: 0.13 }),
    tone({ dur: 0.18, f0: 784, amp: 0.5, attack: 0.26 }),
    tone({ dur: 0.3, f0: 1047, amp: 0.55, attack: 0.39 }),
  ])
)

console.log('done')
