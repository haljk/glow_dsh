/**
 * Emit the recorded conformance vectors as a Kotlin source file.
 *
 * The JSON copy stays for humans; the Kotlin copy exists so the Android unit
 * tests need no JSON parser and no Android runtime — they run on a plain JVM.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const jsonPath = path.join(HERE, '..', 'android', 'app', 'src', 'test', 'resources', 'conformance', 'vectors.json')
const outPath = path.join(
  HERE, '..', 'android', 'app', 'src', 'test', 'kotlin', 'dev', 'haljk', 'glow', 'core', 'ConformanceVectors.kt',
)

const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))

/** Kotlin raw string: safe for prompts, which contain no triple quotes. */
function raw(text) {
  const value = String(text ?? '')
  if (value.includes('"""')) throw new Error('prompt contains a triple quote')
  return `"""${value}"""`
}

function kotlinList(values) {
  return `listOf(${values.map((v) => `"${v}"`).join(', ')})`
}

const lines = []
lines.push('package dev.haljk.glow.core')
lines.push('')
lines.push('// GENERATED FILE — do not edit.')
lines.push('// Produced by tools/emit-kotlin-vectors.js from the recorded DSH implementation.')
lines.push(`// Recorded at ${data.generatedAt}`)
lines.push('//')
lines.push('// These are the product rules as the shipped Node version actually behaves.')
lines.push('// The Android port must reproduce every expected value exactly.')
lines.push('')
lines.push('internal object ConformanceVectors {')
lines.push('')
lines.push('    data class ComposeCase(')
lines.push('        val name: String,')
lines.push('        val presetId: String?,')
lines.push('        val note: String,')
lines.push('        val roles: List<String>,')
lines.push('        val expectedPrompt: String,')
lines.push('        val expectedPresetId: String,')
lines.push('        val expectedReferences: Int,')
lines.push('        val expectedRoles: List<String>,')
lines.push('    )')
lines.push('')
lines.push('    val compose: List<ComposeCase> = listOf(')
for (const c of data.compose) {
  const roles = (c.input.refs ?? []).map((r) => r.role)
  lines.push('        ComposeCase(')
  lines.push(`            name = "${c.name}",`)
  lines.push(`            presetId = ${c.input.presetId ? `"${c.input.presetId}"` : 'null'},`)
  lines.push(`            note = ${raw(c.input.note ?? '')},`)
  lines.push(`            roles = ${kotlinList(roles)},`)
  lines.push(`            expectedPrompt = ${raw(c.expected.prompt)},`)
  lines.push(`            expectedPresetId = "${c.expected.presetId ?? ''}",`)
  lines.push(`            expectedReferences = ${c.expected.references},`)
  lines.push(`            expectedRoles = ${kotlinList(c.expected.roles ?? [])},`)
  lines.push('        ),')
}
lines.push('    )')
lines.push('')
lines.push('    data class FitCase(')
lines.push('        val name: String,')
lines.push('        val sourceWidth: Int,')
lines.push('        val sourceHeight: Int,')
lines.push('        val budget: Int,')
lines.push('        val expectedText: String,')
lines.push('        val expectedRatio: String,')
lines.push('        val expectedPixels: Int,')
lines.push('    )')
lines.push('')
lines.push('    val fitting: List<FitCase> = listOf(')
for (const f of data.fitting) {
  lines.push(
    `        FitCase("${f.name}", ${f.input.sourceWidth}, ${f.input.sourceHeight}, ${f.input.budget}, ` +
      `"${f.expected.text}", "${f.expected.ratio}", ${f.expected.pixels}),`,
  )
}
lines.push('    )')
lines.push('')
lines.push('    data class RejectCase(')
lines.push('        val name: String,')
lines.push('        val presetId: String?,')
lines.push('        val note: String,')
lines.push('        val roles: List<String>,')
lines.push('    )')
lines.push('')
lines.push('    val rejects: List<RejectCase> = listOf(')
for (const r of data.rejects) {
  const roles = (r.input.refs ?? []).map((x) => x.role)
  lines.push(
    `        RejectCase("${r.name}", ${r.input.presetId ? `"${r.input.presetId}"` : 'null'}, ` +
      `${raw(r.input.note ?? '')}, ${kotlinList(roles)}),`,
  )
}
lines.push('    )')
lines.push('}')

fs.mkdirSync(path.dirname(outPath), { recursive: true })
fs.writeFileSync(outPath, lines.join('\n') + '\n', 'utf8')
console.log(`emitted ${data.compose.length} compose, ${data.fitting.length} fitting, ${data.rejects.length} reject cases`)
console.log(outPath)
