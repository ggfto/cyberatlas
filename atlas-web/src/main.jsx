import React from 'react'
import { createRoot } from 'react-dom/client'
// O CSS do kit e importado pelo index.css (camada `kit`); ver a nota la.
import './index.css'
import App from './App.jsx'
import Embed from './Embed.jsx'
import LoginPopup from './LoginPopup.jsx'
import { isLoginPopupPath, parseEmbedParams, shouldRenderEmbed } from './lib/embed.js'

const embed = shouldRenderEmbed(window)
// o popup de login do /embed e top-level; enquadrado, vale a regra de sempre (so o Embed)
const loginPopup = !embed && isLoginPopupPath(window.location.pathname)

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    {embed ? <Embed {...parseEmbedParams(window.location.search)} /> : loginPopup ? <LoginPopup /> : <App />}
  </React.StrictMode>,
)
