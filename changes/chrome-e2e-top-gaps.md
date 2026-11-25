- Chrome end-to-end tests cover the riskiest open gaps:
  - a sensitive site of the grant opens only on the phone's signed allowance;
  - "Not on this site" switched on mid-run;
  - a step replayed after the service worker restarts;
  - a click that would place an order or pay, known by the page's semantics alone.

  A known bug is kept as `todo`: the taf's click on a link to a sensitive site the phone didn't allow still asks that site for
  its page.
