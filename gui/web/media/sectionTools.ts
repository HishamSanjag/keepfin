import {createContext} from 'react'
import type {ReactNode} from 'react'

/** The edit controls of the section being edited (handle, eye, Row/Grid); its frame shows them in its header. */
export const SectionEditTools = createContext<ReactNode>(null)
