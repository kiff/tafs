- Chrome end-to-end tests cover the last gaps that touch your data:
  - Coach's own send starting a run in your tab;
  - Coach's read of a page with frames;
  - a page too large to send even when cut.

  A known bug is kept as `todo`: a click that ran, whose page then grew too large to send, is answered as a failure.
