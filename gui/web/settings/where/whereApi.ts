import type {WhereItemView, WhereState} from '../../../shared/where'
import {request} from '../../bridge'

/** Where the library's files are (gui/shared/where.ts). Asking marks the page as watching: main pushes `where` while it is. */
export const getWhere = () => request<WhereState>({op: 'whereState'})

/** The places one library item is on, for the per-file line. */
export const getWhereItem = (id: string) => request<WhereItemView>({op: 'whereItem', id})
