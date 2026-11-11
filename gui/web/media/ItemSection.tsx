// A Section of library items: the cards share one selection, context menu and play context.
import type {LibraryItem} from '../../shared/library'
import type {CardKind, SectionStyle} from '../../shared/core/layout'
import {removeFromContinue} from '../player/play'
import {CollectionContext, ItemCard, sectionCell} from '../library/cells'
import {useCollectionController} from '../library/controller'
import {SECTION_LIMIT, Section} from './Section'

type Props = {
  id: string
  title: string
  count?: number
  blurb?: string
  kind: CardKind
  items: LibraryItem[]
  style?: SectionStyle
  onSeeAll?: () => void
  /** The cards are things to pick up again: they show the time left and a ✕ that takes them off the row. */
  resume?: boolean
}

export function ItemSection({items, resume, ...section}: Props) {
  const shown = items.slice(0, SECTION_LIMIT)
  const controller = useCollectionController(shown)
  return (
    <CollectionContext.Provider value={controller.api}>
      <Section
        {...section}
        items={shown}
        itemKey={(item) => item.id}
        renderItem={(item, index) => (
          <ItemCard id={item.id} kind={section.kind} state={sectionCell(index)} resume={resume ? {onRemove: () => removeFromContinue(item)} : undefined} />
        )}
      />
      {controller.menuNode}
    </CollectionContext.Provider>
  )
}
