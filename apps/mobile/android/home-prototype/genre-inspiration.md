# Genre chip layout references

Research for the responsive genre study, 2026-09-09. These are first-party
projects and demonstrations. None is an exact example of Aurelia's proposed
chip component: the direction combines row geometry with responsive type.

## References

| Reference | What it demonstrates | What to borrow | What to leave behind |
| --- | --- | --- | --- |
| [Flickr: Justified Layout](https://flickr.github.io/justified-layout/) | Different source aspect ratios become rows of boxes with calculated widths and positions. Its target row height can vary to make a row fit. | Full rows with different interior divisions; let the contents determine widths instead of imposing equal columns. | Photo-specific aspect-ratio preservation and varying row heights. Genre chips can keep a consistent touch height. Its optional incomplete final row also differs from the requested filled rows. |
| [Axis-Praxis: Resize textbox with variable fonts](https://www.axis-praxis.org/blog/2016-11-24/10/demo-resize-textbox-with-variable-fonts) | A resizable text box adjusts the font's width axis; horizontal scaling takes over when the width-axis range cannot fill it. Width is found through repeated measurement. | Fit a label with designed narrow/wide letterforms while retaining a consistent text height. | Unrestricted distortion when a box becomes extreme. Repack the chips before forcing short names to become enormous. |
| [Laurence Penney: fit-to-width](https://github.com/Lorp/fit-to-width) | An implementation that measures text and adjusts width, spacing, or horizontal scaling in a configurable sequence. | Separate row allocation from label fitting; constrain fitting to an intentional range. | Treating its historical browser-support notes as current compatibility advice; importing a library merely for a small prototype. |
| [David Jonathan Ross: Fit specimen](https://djr.com/fit/) | The specimen itself responsively fits words to the page width. Its variable font ranges from very narrow to very wide forms. | The lively rhythm of broad and narrow words within aligned edges. This is the strongest visual reference for expressive fitting. | The font's extreme density at small sizes: the creator describes it as display type for a few words at enormous sizes. Use a more readable typeface for tappable music genres. |

## Proposed translation to Aurelia

The following is design judgment informed by those references, rather than a
claim that they already implement this exact interaction:

- Measure each label at a preferred size and width, then add comfortable
  horizontal padding to derive its natural chip width.
- Choose row breaks together, considering the available screen width and
  the whole genre set. Avoid a fixed 3/2/3 pattern or predetermined columns.
- Distribute each row's remaining width in proportion to those natural
  widths. Every row reaches both content margins while a short name remains
  visibly smaller than a long one.
- Fit type after allocation, with modest width-axis adjustments. Reflow
  should solve large imbalances before typography does.
- Keep gaps, chip height, corners, and label baselines steady. Differing
  widths and changing row breaks provide movement without arbitrary visual
  noise.
- Retain every genre, predictable reading order, and stable packing for an
  unchanged width/data set. Changing the viewport or genre list may produce
  a different number of rows.

The key distinction is **justified rows** (a layout rule) versus **expressive
letterforms** (a visual treatment). Neither requires equal chip widths, and
the first should remain attractive even with ordinary readable text.
