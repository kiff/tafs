- Chrome end-to-end tests cover the rest of the ranked gaps:
  - controls the person can't use;
  - planque's stop mid-act;
  - a page's dialogs as it loads, and "leave this page?";
  - which senders may command the extension;
  - the floating bar and the badge after the person takes their tab back.

  Five known bugs are kept as `todo` tests:
  - a page's alert or confirm as it loads reaches Chromium;
  - a field in a disabled fieldset takes the taf's fill;
  - so does a read-only field;
  - a disabled option can be chosen;
  - unchecking a radio answers ok though it stays checked.
