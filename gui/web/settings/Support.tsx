import type {SupportLinkId} from '../../shared/support'
import {messageOf, native, request} from '../bridge'
import {toast} from '../toasts'
import {Icon} from '../ui/Icon'
import avatar from '../assets/avatar.jpg'

const options: {id: Exclude<SupportLinkId, 'github'>; name: string; note: string; icon: string}[] = [
  {id: 'sponsors', name: 'GitHub Sponsors', note: 'Monthly or one-time', icon: 'heart'},
  {id: 'kofi', name: 'Ko-fi', note: 'Buy me a coffee', icon: 'coffee'},
  {id: 'paypal', name: 'PayPal', note: 'Send any amount', icon: 'wallet'},
]

export function Support() {
  async function open(id: SupportLinkId) {
    try {
      await request({op: 'openSupportLink', id})
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
    }
  }
  return (
    <div className="support">
      <section className="support-card" aria-label="About the developer">
        <img className="support-avatar" src={avatar} alt="Hisham Sanjag" width={96} height={96} />
        <h3>Hisham Sanjag</h3>
        <button type="button" className="support-handle" disabled={!native} onClick={() => void open('github')}>
          @HishamSanjag
        </button>
        <p>I build Keepfin in my spare time. If it saves you time, you can support its development.</p>
      </section>
      <ul className="support-options" aria-label="Ways to support Keepfin">
        {options.map((option) => (
          <li key={option.id}>
            <button type="button" className="support-option" disabled={!native} onClick={() => void open(option.id)}>
              <span className="support-option-icon">
                <Icon name={option.icon} size={22} />
              </span>
              <span className="support-option-text">
                <strong>{option.name}</strong>
                <span>{option.note}</span>
              </span>
              <Icon name="external" size={16} />
            </button>
          </li>
        ))}
      </ul>
      <p className="muted small support-thanks">Thank you. Keepfin stays free either way.</p>
    </div>
  )
}
