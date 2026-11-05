// Publishes every spring token as CSS custom properties, so stylesheets and view transitions move on the same curves:
//   transition: transform var(--spring-snappy-ms) var(--spring-snappy);
// base.css already collapses durations under prefers-reduced-motion.
import {motion, springEasing} from './spring'

export function installSpringVars(root: HTMLElement = document.documentElement) {
  for (const [name, token] of Object.entries(motion)) {
    const {easing, duration} = springEasing(token)
    root.style.setProperty(`--spring-${name}`, easing)
    root.style.setProperty(`--spring-${name}-ms`, `${duration}ms`)
  }
}
