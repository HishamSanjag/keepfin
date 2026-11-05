// Points git at the committed hooks in scripts/dev/hooks. Safe to run again; skips quietly outside a git checkout.
import {execFileSync} from 'node:child_process'
import {existsSync} from 'node:fs'

if (existsSync('.git')) {
  try {
    execFileSync('git', ['config', 'core.hooksPath', 'scripts/dev/hooks'], {stdio: 'ignore'})
    console.log('Git hooks installed (scripts/dev/hooks). Skip once with: git commit --no-verify')
  } catch {
    // git missing or not usable: hooks are optional, so do not fail npm ci.
  }
}
