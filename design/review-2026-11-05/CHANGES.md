# Trismegistus for Android: review and polish pass

Compared with the 0.1.2 screenshots and the approval draft. Numbers are stable, so you can answer per item ("12 yes, 27 no"). `[P1]` to `[P11]` point to your problem list; everything else I found during the review.

## #pairing

1. Layout is now two labelled steps, 01 get a code, 02 scan it or paste it. **Why:** the order of the task was only implied. [P11]
2. The QR viewfinder shrinks from a 225 dp square to a 72 dp dark tile with the same words and icon. **Why:** it dominated and made pasting look like an afterthought. [P11]
3. The command sits in a block at the top, wraps in full and has a copy button. **Why:** it was cut off, and it is the first thing you need. [P11]
4. Code and relay fields get persistent labels. **Why:** the relay URL read as a mystery value, and a placeholder is not a label. [P11]
5. The viewfinder's yellow outline is gone. **Why:** two yellow objects on one screen; Pair is the primary.
6. Tapping Pair with a bad code shows an inline error next to the field. **Why:** Pair stays yellow when empty, so it needs an answer.

## #list

7. The search icon in the bar becomes the filter icon, and it focuses the filter field. **Why:** FIXED; the old icon and the field did the same job.
8. The per-row app icon is removed; only pin or lock remains before the meta. **Why:** every row in the App tab repeated the tab's own icon.
9. Rows shrink from 65 dp to 64 dp, and the fixed spacing now fits 10 rows and part of the next above the FAB. **Why:** all spacing comes from the scale.
10. RUN keeps blue with a live timer; APPROVAL is a magenta square, ink label and live timer. **Why:** both must read without colour alone, and magenta is never text.
11. The FAB's soft shadow is removed. **Why:** no shadows outside the drawer scrim and the sheet.
12. A filter with no match gets its own message and a Clear filter button. **Why:** the empty result had no way out.
13. The header counts filtered results while a filter is active. **Why:** `APP // 14` would be wrong next to 3 rows.

## #loading

14. Skeleton rows in the exact row geometry replace the empty state. **Why:** the false "no conversations" flash. [P1]
15. Tab counts show `-`, the header `APP // --` and `LOADING`; the filter is disabled. **Why:** no zeros that later jump. [P1]
16. The FAB stays. **Why:** starting a conversation does not depend on the list, and nothing moves when it arrives.

## #drawer

17. Faint contour lines fill the empty column. **Why:** one item on a flat grey column read as unfinished, and no sections may be added. [P7]
18. The scrim is a clear, even ink at 60% and covers the top bar. **Why:** the old wash was pale and the list competed with the drawer. [P7]
19. Hairlines under the header and above the footer are darker. **Why:** on #666 the old ones barely separated the three zones.

## #conversation

20. The turn's tool row has one form: `>_ terminal sleep 25 … 25.0 s ›`. **Why:** the live and reloaded rows differed. [P4]
21. With several commands the same row reads `3 commands <last command> <total> ›`. **Why:** your rule that a run sums up in one marker row.
22. The row opens the transcript sheet in both states. **Why:** one row, one behaviour. [P4]
23. Rows and buttons are 44 dp; message gaps are 16 dp. **Why:** the shipped gaps were uneven.
24. Send stays yellow on an empty field; tapping it then focuses the field. **Why:** your approval rule demotes Send, so it must be yellow otherwise. Shipped is grey when empty, so please confirm.
25. The more menu holds Transcript and New conversation. **Why:** the shipped contents are not visible; these two are the actions known to exist.

## #run

26. The subtitle reads `app · running`. **Why:** the cut id said nothing. [P2]
27. The working line reads `WORKING 00:14 · sleep 30`, with the actual command and a ticking timer. **Why:** "00:00 · 1 command" showed no life. [P3]
28. The tool row carries its own ticking time and a pulsing green square. **Why:** "0.0 s" was wrong during the run. The run timer and command timer differ on purpose. [P3]
29. The working line is pinned above the message box. **Why:** progress stays visible if you scroll up.
30. The sweep is ink instead of yellow. **Why:** Steer is the one yellow here.
31. Your message shows its time at once. **Why:** `YOU` had no time until a reload. [P9]
32. `YOU // SENDING` shows until the relay accepts the message, then the time. **Why:** tells a send in flight from a sent message. [P9]

## #transcript

33. The title reads `TRANSCRIPT // RUNNING` or `// DONE`. **Why:** the cut id. [P5]
34. The orange bar becomes a quiet row: time, `terminal`, command, green square, timer. **Why:** orange is not a token and it was the loudest thing on screen. [P5]
35. The COMMANDS / ALL TOOLS switch is removed. **Why:** you dropped such filters on the Mac. [P5]
36. A close icon joins the handle. **Why:** the sheet had no visible exit.
37. The explanation sits in a panel under its entry; a finished entry shows its output there. **Why:** it belongs to the command, not the sheet.
38. The sheet stays ink, with contour lines in the dark panel colour. **Why:** keeps the shipped identity.

## #tray

39. The remove button is a 20 dp ink corner with a 14 dp X and a 48 dp touch area. **Why:** the old square covered a third of the image. [P6]
40. The caption reads `1 of 4 · resized · location removed`, sentence case. **Why:** it was long and shouty. [P6]
41. The thumbnail is a true 72 dp square. **Why:** it was offset and cut by the old button.
42. The rail holds up to 4 thumbnails. **Why:** the caption promises 4.

## #approval

43. The message box is the one-row 04c, not the draft's two rows. **Why:** FIXED.
44. Stop stays red outline; Steer is demoted to plain and icon-only. **Why:** FIXED.
45. The draft's yellow line on the banner and the yellow sweep are removed. **Why:** Once is the only yellow.
46. The banner's shadow becomes a 1 dp ink outline. **Why:** no shadows outside the two exceptions.
47. The note `"always" is never offered` is removed. **Why:** a design note, not product copy.
48. The subtitle reads `trismegistus · waiting`. **Why:** same as item 26.
49. `YOU // 23:50` becomes `YOU // 12:40`. **Why:** it contradicted the draft's 12:41 status bar.
50. The working line is `WAITING 00:12 · approval` on a still rail. **Why:** nothing is running.
51. A Hermes text and code block were added. **Why:** no shipped screen shows the code block and its copy button (see Sample data).
52. Deny has ink text, with a red outline and red X. **Why:** red on white is 4.4:1, under the 4.5:1 floor at 13 sp, and no darker red is in the list. The same applies to Unpair. Shipped uses red text.

## #settings

53. Every row is 64 dp with a title and a subline. **Why:** rows were uneven. [P8]
54. Square switches are 40 × 20 with a light track and an ink thumb, no yellow. **Why:** three black blocks read heavy, and this screen has no yellow primary. [P8]
55. Push reads `Not set up: alerts need the app running` with a Set up button that shows the next step. **Why:** no next step before. [P8]
56. Row titles use body-strong. **Why:** clearer hierarchy over the sublines.
57. The revoke command gets a block and a copy button. **Why:** it is meant to be pasted elsewhere.
58. Unpair asks for confirmation. **Why:** it is consequential and hard to undo.

## #new

59. A short hint sits just above the message box, with the model and relay as a readout. **Why:** the page was blank. [P10]
60. The first message sets the title, and the screen then behaves like any conversation. **Why:** keeps one flow. Assumption, please confirm.

## Across screens

61. Inline SVG icons are one stroke family with square caps. **Why:** mixed stroke styles today.
62. Keyboard focus, screen reader names and reduced motion are specified. **Why:** they map to Compose semantics.

# Departures from FIXED

None intended. Four readings are worth your check:

- **A. Yellow on #approval:** the title rule still has its yellow segment, because the rule is FIXED. I read "Once is the only yellow" as the only yellow control. Say so if the rule should go grey there.
- **B. Title rule:** your text puts it under a conversation's bar; the shipped app has it on every bar, so I kept it everywhere.
- **C. Model picker:** 32 dp visible inside a 48 dp touch area, not the 44 dp button size. At 44 dp it crowds the 56 dp bar.
- **D. Non-scale sizes:** 64 dp for settings rows, 196 dp for menus and 8 dp marks are component sizes, not spacing. The 3 dp and 1.5 dp values come from FIXED.

Other notes:

- The app's token set is below the general 16 px body floor (labels at 10 to 12 sp). That is your type scale and I kept it.
- No gesture bar is drawn, to match your screenshots. In Compose the message box needs navigation bar and keyboard insets.
- The contour radius is twice Endfield's spec, so the lines fill the phone as they do in the screenshots.

# Sample data

Real content from the screenshots: conversation titles, QA messages, times, counts, device and relay names, the pairing and revoke commands, and the approval draft's command, reason and title.

Sample, invented for this prototype:

- `APP // 14` uses the screenshot's count, not the 13 in your example. To reach 14 rows, `Relay notes` and `Release 0.1.2 checklist` are invented, `Clean build cache` comes from the draft, and the end of the xMuramasa title and its meta are guessed.
- `Android app` carrying RUN 00:07 comes from the drawer screenshot; `Clean build cache` carrying APPROVAL 00:12 is sample.
- All rows under Telegram, Terminal and More. What More's chevron opens is not visible in the screenshots, so here it is a plain tab.
- All run and command durations, the `09:48:02` and `12:40:31` stamps, and the transcript's `No output.`
- The approval screen's Hermes text and `./gradlew clean assembleRelease`.
- `sonnet-5-5` and `haiku-5-5` in the model menu.
- All switch sublines, and the Push text and `trismegistus-relay push enable --device emulator`. That command is a placeholder; please replace it with the real one.
- The scan result `K7QD-2MXP-9TRB`, and the replies after you send, steer or answer an approval.
- The tray thumbnail, a crop of today-04-conversation.
- Opening a conversation other than QA or Clean build cache shows "This conversation has no history in the prototype."
