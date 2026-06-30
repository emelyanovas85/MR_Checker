#!/usr/bin/env node
// patch-index.mjs — rewrites server.tool() calls for SDK 1.29.0 compatibility
// Before: server.tool(name, z.object({...}).describe(desc), handler)
// After:  server.tool(name, desc, z.object({...}).shape, handler)

import { readFileSync, writeFileSync } from 'fs';

const filePath = process.argv[2] || '/app/index.js';
let src = readFileSync(filePath, 'utf8');

function rewriteToolCalls(source) {
  const result = [];
  let i = 0;
  const marker = 'server.tool(';

  while (i < source.length) {
    const idx = source.indexOf(marker, i);
    if (idx === -1) {
      result.push(source.slice(i));
      break;
    }

    result.push(source.slice(i, idx));
    i = idx + marker.length;

    // Find the matching closing paren
    let depth = 1;
    let argEnd = i;
    while (argEnd < source.length && depth > 0) {
      const ch = source[argEnd];
      if (ch === '(' || ch === '{' || ch === '[') depth++;
      else if (ch === ')' || ch === '}' || ch === ']') depth--;
      if (ch === '"' || ch === "'" || ch === '`') {
        const quote = ch;
        argEnd++;
        while (argEnd < source.length) {
          if (source[argEnd] === '\\') { argEnd += 2; continue; }
          if (source[argEnd] === quote) break;
          argEnd++;
        }
      }
      if (depth > 0) argEnd++;
    }

    const argsStr = source.slice(i, argEnd);
    i = argEnd + 1;

    const args = splitTopLevel(argsStr);

    if (args.length === 3) {
      const nameArg = args[0].trim();
      const schemaArg = args[1].trim();
      const handlerArg = args[2].trim();

      const describeMatch = schemaArg.match(/^(z\.object\([\s\S]*\))\.describe\(([\s\S]*)\)$/);
      if (describeMatch) {
        const zodObj = describeMatch[1].trim();
        const descExpr = describeMatch[2].trim();
        result.push(`server.tool(${nameArg},\n  ${descExpr},\n  ${zodObj}.shape,\n  ${handlerArg})`);
        continue;
      }
    }

    result.push(`server.tool(${argsStr})`);
  }

  return result.join('');
}

function splitTopLevel(str) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < str.length; i++) {
    const ch = str[i];
    if (ch === '(' || ch === '{' || ch === '[') depth++;
    else if (ch === ')' || ch === '}' || ch === ']') depth--;
    else if (ch === '"' || ch === "'" || ch === '`') {
      const q = ch;
      i++;
      while (i < str.length) {
        if (str[i] === '\\') { i++; continue; }
        if (str[i] === q) break;
        i++;
      }
    } else if (ch === ',' && depth === 0) {
      parts.push(str.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(str.slice(start));
  return parts;
}

const patched = rewriteToolCalls(src);
const count = (patched.match(/server\.tool\(/g) || []).length;
writeFileSync(filePath, patched, 'utf8');
console.log(`patch-index: rewrote ${count} server.tool() call(s) in ${filePath}`);
