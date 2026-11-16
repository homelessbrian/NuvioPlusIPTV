'use strict';
// "On Demand" tab. Placeholder; owned by the ondemand work stream.
window.NuvioUI.registerTab({ id: 'ondemand', title: 'On Demand', mount(root) { const p = document.createElement('p'); p.className = 'dim'; p.style.padding = '24px'; p.textContent = 'On Demand is coming soon.'; root.append(p); } });
