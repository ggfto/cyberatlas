import React from 'react'
import { createRoot } from 'react-dom/client'
// O CSS do kit e importado pelo index.css (camada `kit`); ver a nota la.
import './index.css'
import App from './App.jsx'
import Embed from './Embed.jsx'
import { parseEmbedParams, shouldRenderEmbed } from './lib/embed.js'

const embed = shouldRenderEmbed(window)

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    {embed ? <Embed {...parseEmbedParams(window.location.search)} /> : <App />}
  </React.StrictMode>,
)
