- Chrome end-to-end tests pin what a page of the run's sites sends by itself after a taf's fill, in a tab of the run's own and
  in the person's joined tab. The fence covers navigations only:
  - a page's own `fetch`, XHR, beacon, image and WebSocket reach another site, as the site's own requests;
  - a form submitted into a frame is fenced;
  - a message to an off-site frame reaches nobody in a run's own tab, and reaches the frame the person's page already had open.

  `docs/chrome.md` now says the fence covers navigations only, not every request.
