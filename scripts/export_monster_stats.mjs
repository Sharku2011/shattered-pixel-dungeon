import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const mobRoot = path.join(root, 'core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs');
const output = path.join(root, 'core/src/main/assets/balance/monster_stats.csv');
const mobs = [];

function javaFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? javaFiles(full) : entry.name.endsWith('.java') ? [full] : [];
  });
}

function matchingBrace(source, open) {
  let depth = 0, state = 'code';
  for (let i = open; i < source.length; i++) {
    const c = source[i], n = source[i + 1];
    if (state === 'line') { if (c === '\n') state = 'code'; continue; }
    if (state === 'block') { if (c === '*' && n === '/') { state = 'code'; i++; } continue; }
    if (state === 'string' || state === 'char') {
      if (c === '\\') { i++; continue; }
      if ((state === 'string' && c === '"') || (state === 'char' && c === "'")) state = 'code';
      continue;
    }
    if (c === '/' && n === '/') { state = 'line'; i++; continue; }
    if (c === '/' && n === '*') { state = 'block'; i++; continue; }
    if (c === '"') { state = 'string'; continue; }
    if (c === "'") { state = 'char'; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return i;
  }
  throw new Error(`Unclosed class body at offset ${open}`);
}

function compact(value) {
  return value.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\r\n]*/g, ' ')
    .replace(/\s+/g, ' ').trim();
}

for (const file of javaFiles(mobRoot)) {
  const source = fs.readFileSync(file, 'utf8');
  const declarations = [...source.matchAll(/\b((?:(?:public|protected|private|static|abstract|final)\s+)*)class\s+(\w+)\s+extends\s+([\w.]+)(?:\s*<[^>{}]+>)?[^{}]*\{/g)];
  for (const match of declarations) {
    const open = match.index + match[0].lastIndexOf('{');
    const close = matchingBrace(source, open);
    const prefix = source.slice(0, match.index);
    const enclosing = mobs.filter(c => c.file === file && c.open < open && c.close > close)
      .sort((a, b) => (a.close - a.open) - (b.close - b.open))[0];
    mobs.push({
      name: match[2], parent: match[3].split('.').at(-1), abstract: /\babstract\b/.test(match[1]),
      file, source, start: match.index, open, close,
      qualified: enclosing ? `${enclosing.qualified}.${match[2]}` : match[2],
      line: prefix.split('\n').length
    });
  }
}

// The declarations are discovered in source order, so recompute nesting for later files/classes too.
for (const mob of mobs) {
  const enclosing = mobs.filter(c => c.file === mob.file && c.open < mob.open && c.close > mob.close)
    .sort((a, b) => (a.close - a.open) - (b.close - b.open))[0];
  mob.qualified = enclosing ? `${enclosing.qualified}.${mob.name}` : mob.name;
  mob.children = mobs.filter(c => c.file === mob.file && c.open > mob.open && c.close < mob.close);
}

const byName = new Map();
for (const mob of mobs) byName.set(mob.name, mob);

function ownCode(mob) {
  let code = mob.source.slice(mob.open + 1, mob.close);
  for (const child of mob.children) {
    const start = child.start - mob.open - 1;
    const end = child.close - mob.open;
    code = code.slice(0, start) + ' '.repeat(Math.max(0, end - start)) + code.slice(end);
  }
  return code;
}

function initializationBodies(mob) {
  const code = ownCode(mob);
  const bodies = [];
  let depth = 0;
  for (let i = 0; i < code.length; i++) {
    if (code[i] === '{' && depth === 0) {
      const before = code.slice(0, i);
      const open = i, close = matchingBrace(code, open);
      const header = before.slice(Math.max(0, before.lastIndexOf('\n') + 1)).trim();
      // Instance initializer blocks hold most of the game's literal mob stats.
      if (!header || header === ';') bodies.push(code.slice(open + 1, close));
      // Constructor bodies are also initialization sources.
      const ctor = new RegExp(`\\b${mob.name}\\s*\\([^;{}]*\\)\\s*$`).test(before);
      if (ctor) bodies.push(code.slice(open + 1, close));
      i = close;
    }
  }
  return bodies;
}

function assigned(body, field) {
  const escaped = field.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return [...body.matchAll(new RegExp(`\\b${escaped}\\s*=\\s*([^;]+);`, 'g'))]
    .map(m => compact(m[1])).filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(' | ');
}

function methodReturns(mob, method, returnType = 'int') {
  const code = ownCode(mob);
  const re = new RegExp(`\\b${returnType}\\s+${method}\\s*\\([^;{}]*\\)\\s*\\{`, 'g');
  const match = re.exec(code);
  if (!match) return '';
  const open = match.index + match[0].lastIndexOf('{');
  const body = code.slice(open + 1, matchingBrace(code, open));
  return [...body.matchAll(/\breturn\s+([^;]+);/g)].map(m => compact(m[1]))
    .filter((v, i, a) => a.indexOf(v) === i).join(' | ');
}

function methodAssignments(mob, methodNames, field) {
  const code = ownCode(mob);
  const values = [];
  const names = methodNames.join('|');
  const re = new RegExp(`\\b(?:void|int|float|double)\\s+(${names})\\s*\\([^;{}]*\\)\\s*\\{`, 'g');
  for (const match of code.matchAll(re)) {
    const open = match.index + match[0].lastIndexOf('{');
    const body = code.slice(open + 1, matchingBrace(code, open));
    const assignments = assigned(body, field);
    if (assignments) values.push(`${match[1]}(): ${assignments}`);
  }
  return [...new Set(values)].join(' | ');
}

const baseFields = {
  defenseSkill: '0', EXP: '1', maxLvl: 'Hero.MAX_LEVEL-1', lootChance: '0',
  baseSpeed: 'Char default: 1', viewDistance: 'Char default: 8'
};

function inheritanceChain(mob) {
  const chain = [];
  let current = mob;
  const seen = new Set();
  while (current && !seen.has(current.name)) {
    chain.push(current);
    seen.add(current.name);
    current = byName.get(current.parent);
    if (!current && chain.at(-1).parent === 'Mob') break;
  }
  return chain;
}

function isMonster(mob) {
  const chain = inheritanceChain(mob);
  return chain.some(c => c.name === 'Mob' || c.parent === 'Mob') && !chain.some(c => c.name === 'NPC');
}

function inheritedValue(mob, field) {
  for (const entry of inheritanceChain(mob)) {
    const bodies = initializationBodies(entry);
    const body = bodies.join('\n');
    let value = '';
    if (field === 'hp') {
      const chained = [...body.matchAll(/\bHP\s*=\s*HT\s*=\s*([^;]+);/g)].map(m => compact(m[1]));
      const ht = [...body.matchAll(/\bHT\s*=\s*([^;]+);/g)].map(m => `HT=${compact(m[1])} (HP may differ)`);
      value = [...new Set(chained)].join(' | ') || [...new Set(ht)].join(' | ');
      if (!value) value = methodAssignments(entry, ['spawn', 'adjustStats', 'setLevel'], 'HT');
    } else if (field === 'attackSkill') value = methodReturns(entry, 'attackSkill');
    else if (field === 'damageRoll') value = methodReturns(entry, 'damageRoll');
    else if (field === 'dr') value = methodReturns(entry, 'drRoll');
    else if (field === 'attackDelay') value = methodReturns(entry, 'attackDelay', 'float');
    else value = bodies.map(initializer => assigned(initializer, field)).filter(Boolean).join(' | ');
    if (!value && field === 'defenseSkill') value = methodAssignments(entry, ['spawn', 'adjustStats', 'setLevel'], field);
    if (value) return entry === mob ? value : `inherited(${entry.qualified}: ${value})`;
    if (entry.parent === 'Mob' && Object.hasOwn(baseFields, field)) return `Mob default: ${baseFields[field]}`;
    if (entry.parent === 'Mob' && field === 'attackSkill') return 'Char default: 0';
    if (entry.parent === 'Mob' && field === 'damageRoll') return 'Char default: 1';
    if (entry.parent === 'Mob' && field === 'dr') return 'Char default: 0';
  }
  return 'dynamic / unresolved';
}

const previous = new Map();
if (fs.existsSync(output)) {
  const lines = fs.readFileSync(output, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean);
  const parseLine = line => {
    const values = [];
    let cell = '', quoted = false;
    for (let i = 0; i < line.length; i++) {
      if (quoted && line[i] === '"' && line[i + 1] === '"') { cell += '"'; i++; }
      else if (line[i] === '"') quoted = !quoted;
      else if (line[i] === ',' && !quoted) { values.push(cell); cell = ''; }
      else cell += line[i];
    }
    values.push(cell);
    return values;
  };
  const oldHeaders = parseLine(lines[0]);
  for (const line of lines.slice(1)) {
    const values = parseLine(line), record = Object.fromEntries(oldHeaders.map((key, i) => [key, values[i] ?? '']));
    if (record.class_name) previous.set(record.class_name, record);
  }
}
const rows = mobs.filter(m => !m.abstract && isMonster(m)).map(m => {
  const own = ownCode(m);
  const notes = [];
  if (/Dungeon\.depth|Dungeon\.depth|Dungeon\.isChallenged|Challenges\./.test(own)) notes.push('depth/challenge-dependent code');
  if (/\bphase\b|Phase|HP\s*=\s*Math\.|HT\s*=\s*Math\./.test(own)) notes.push('phase or runtime HP/HT changes');
  if (/public\s+int\s+damageRoll\s*\([^)]*\)\s*\{[^}]*weapon\./s.test(own)) notes.push('damage depends on equipped weapon');
  if (/\b(Random\.|Random\s*\.)/.test(own)) notes.push('randomized behavior/formula');
  const relative = path.relative(root, m.file).split(path.sep).join('/');
  const packageName = m.source.match(/^package\s+([\w.]+);/m)?.[1] ?? '';
  const className = `${packageName}.${m.qualified}`;
  const old = previous.get(className) ?? {};
  return {
    class_name: className, parent_class: m.parent,
    hp_ht_init: inheritedValue(m, 'hp'),
    attack_skill_formula: inheritedValue(m, 'attackSkill'),
    damage_roll_formula: inheritedValue(m, 'damageRoll'),
    defense_skill_init: inheritedValue(m, 'defenseSkill'),
    dr_formula: inheritedValue(m, 'dr'),
    base_speed_init: inheritedValue(m, 'baseSpeed'),
    attack_delay_formula: inheritedValue(m, 'attackDelay'),
    view_distance_init: inheritedValue(m, 'viewDistance'),
    exp_init: inheritedValue(m, 'EXP'),
    max_level_init: inheritedValue(m, 'maxLvl'),
    loot_chance_init: inheritedValue(m, 'lootChance'),
    dynamic_notes: notes.join('; '),
    hp_multiplier: old.hp_multiplier || '1.0',
    damage_multiplier: old.damage_multiplier || '1.0',
    accuracy_multiplier: old.accuracy_multiplier || '1.0',
    defense_multiplier: old.defense_multiplier || '1.0',
    dr_multiplier: old.dr_multiplier || '1.0',
    speed_multiplier: old.speed_multiplier || '1.0',
    loot_multiplier: old.loot_multiplier || '1.0',
    attack_delay_multiplier: old.attack_delay_multiplier || '1.0',
    view_distance_multiplier: old.view_distance_multiplier || '1.0',
    exp_multiplier: old.exp_multiplier || '1.0',
    hp_stddev: old.hp_stddev || '0',
    damage_stddev: old.damage_stddev || '0',
    accuracy_stddev: old.accuracy_stddev || '0',
    speed_stddev: old.speed_stddev || '0',
    source_file: relative, source_line: m.line
  };
});

const columns = [
  'class_name', 'parent_class', 'hp_ht_init', 'attack_skill_formula', 'damage_roll_formula',
  'defense_skill_init', 'dr_formula', 'base_speed_init', 'attack_delay_formula',
  'view_distance_init', 'exp_init', 'max_level_init',
  'loot_chance_init', 'dynamic_notes', 'hp_multiplier', 'damage_multiplier',
  'accuracy_multiplier', 'defense_multiplier', 'dr_multiplier', 'speed_multiplier',
  'loot_multiplier', 'attack_delay_multiplier', 'view_distance_multiplier',
  'exp_multiplier', 'hp_stddev', 'damage_stddev', 'accuracy_stddev', 'speed_stddev',
  'source_file', 'source_line'
];
const csv = value => {
  const text = String(value ?? '');
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
};
rows.sort((a, b) => a.class_name.localeCompare(b.class_name));
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${columns.join(',')}\n${rows.map(row => columns.map(column => csv(row[column])).join(',')).join('\n')}\n`, 'utf8');
console.log(`Wrote ${rows.length} monster rows to ${path.relative(root, output)}`);
