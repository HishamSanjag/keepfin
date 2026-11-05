// "How downloads work": what Keepfin does with each kind of link, and why. The text comes from
// content/how-downloads-work.json (shared with Android); this file only lays it out.
import guide from '../../../content/how-downloads-work.json'
import {navigate} from '../nav'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon, type IconName} from '../ui/Icon'

type Kind = {
  id: string
  icon: string
  title: string
  sites: string
  what: string
  why: string
  notes?: string[]
  platformNotes?: {desktop?: string; android?: string}
  platforms?: string[]
  action?: 'accounts'
}
type Fact = {icon: string; title: string; text: string}

const kinds = (guide.kinds as Kind[]).filter((kind) => !kind.platforms || kind.platforms.includes('desktop'))
const facts = guide.facts as Fact[]

export function HowItWorks({onClose}: {onClose: () => void}) {
  return (
    <Dialog
      title={guide.title}
      eyebrow="Downloads"
      labelledBy="how-it-works-title"
      className="dialog-how"
      onClose={onClose}
      footer={
        <Button variant="primary" onClick={onClose}>
          Got it
        </Button>
      }
    >
      <p className="how-intro">{guide.intro}</p>
      <ol className="how-steps" aria-label="What happens to a link">
        {guide.steps.map((step, index) => (
          <li key={step.title}>
            <span className="how-step-num tabular" aria-hidden="true">
              {index + 1}
            </span>
            <strong>{step.title}</strong>
            <span>{step.text}</span>
          </li>
        ))}
      </ol>
      <h3 className="how-heading">Each kind of link</h3>
      <ul className="how-kinds">
        {kinds.map((kind) => {
          const note = kind.platformNotes?.desktop
          return (
            <li key={kind.id} className="how-kind" data-kind={kind.id}>
              <span className="how-kind-icon" aria-hidden="true">
                <Icon name={kind.icon as IconName} size={18} />
              </span>
              <div className="how-kind-body">
                <h4>{kind.title}</h4>
                <p className="how-sites">{kind.sites}</p>
                <p>{kind.what}</p>
                <p className="how-why">
                  <span className="how-why-lead">Why: </span>
                  {kind.why}
                </p>
                {!!kind.notes?.length && (
                  <ul className="how-notes">
                    {kind.notes.map((text) => (
                      <li key={text}>{text}</li>
                    ))}
                  </ul>
                )}
                {note && <p className="how-note">{note}</p>}
                {kind.action === 'accounts' && (
                  <div>
                    <Button
                      size="sm"
                      icon="key"
                      onClick={() => {
                        onClose()
                        navigate('Settings', 'accounts')
                      }}
                    >
                      Accounts &amp; cookies
                    </Button>
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ul>
      <h3 className="how-heading">Good to know</h3>
      <ul className="how-facts">
        {facts.map((fact) => (
          <li key={fact.title}>
            <Icon name={fact.icon as IconName} size={15} />
            <div>
              <strong>{fact.title}</strong>
              <span>{fact.text}</span>
            </div>
          </li>
        ))}
      </ul>
    </Dialog>
  )
}
