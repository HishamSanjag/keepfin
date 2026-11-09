import {createRoot} from 'react-dom/client'
import {App} from './App'
import {initAppearance} from './appearance'
import {installSpringVars} from './motion/cssVars'
import {startStore} from './store'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/components.css'
import './styles/shell.css'
import './styles/capture.css'
import './styles/preview.css'
import './styles/queue.css'
import './styles/library.css'
import './styles/gallery.css'
import './styles/spaces.css'
import './styles/settings.css'
import './styles/setup.css'
import './styles/activity.css'
import './styles/home.css'
import './styles/home-follow.css'
import './styles/atelier.css'
import './styles/player.css'

initAppearance()
installSpringVars()
startStore()
createRoot(document.getElementById('root')!).render(<App />)
