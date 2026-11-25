- Chrome end-to-end tests: the run's own tabs. They cover:
  - the person's click there pauses the run;
  - dragging the run's tab out of its group, or a tab of the person's into it, changes nothing the run may read;
  - two runs of two tafs at once each work only in their own tabs.

  Three known bugs are kept as `todo` tests:
  - an address the person types in a run's own tab is read by the run;
  - so is the page their Back or Forward lands on;
  - a second taf of the same name closes the first taf's kept tab.
