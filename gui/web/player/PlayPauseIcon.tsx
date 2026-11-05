import {useEffect, useRef} from 'react'
import {motion, playPausePath, useSpring} from '../motion/spring'

export function PlayPauseIcon({playing, size}: {playing: boolean; size: number}) {
  const pathRef = useRef<SVGPathElement>(null)
  const spring = useSpring(playing ? 1 : 0, motion.snappy, (v) => {
    pathRef.current?.setAttribute('d', playPausePath(v))
  })

  useEffect(() => {
    spring.to(playing ? 1 : 0)
  }, [playing, spring])

  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path ref={pathRef} d={playPausePath(playing ? 1 : 0)} fill="currentColor" />
    </svg>
  )
}
