# Task Manager (Startup page)
This page handles Task group creation. The distinction between task groups is 
their name and the script they're running. A simple shell UI has already been 
made for this page.
## Task Group Page
    This page can be accessed by clicking on any group in the task manager. 
    This page will contain the individual browser tasks that belong to a task group.
    There will be a + button in a similar position and style to the one in Task 
    Manager. This button will trigger a popup that asks for:
    1. The profiles to create tasks for. This will be a dropdown menu where you 
        first select a profile group, then you can either select all profiles
        from that group or check off multiple profiles to use. Profiles will
        displayed as <Profile Name> (<Profile Email Address>).
    2. The proxy group to use. If a user selects a group with 50 proxies but
        only wants to create 10 profiles, the first 10 proxies from the list
        will be assigned. Proxies groups do not "dispense" proxies as they
        don't remove the proxies that are used. They stay the same until
        the user edits the group in the proxies tab.
    3. The user can check a box to indicate whether or not to warm the
        session with activiy. Off by default.
    The create button will be at the bottom of the popup.
    This page also will contain a Start All and Stop All button for tasks, which can
    start or stop all tasks in the group. It will also have a change proxies button
    which allows the user to select a new group of proxies to use for the tasks.
    If a user selects a new proxy group with 10 proxies but there are 20 tasks, only
    the first 10 tasks will receive new proxies and the last 10 will remain unchanged.
### Task Container
    This represents a single task in the Task Group page. They will appear
    in rows (1 per row) and span the entire width of the available space in the UI.
    They will display the task name, status (custom per script), and live log
    on the left and action buttons on the right.

#### Live Log Display
    Each TaskRow has a log label on the left side (below the task name,
    status, and proxy info) that displays live log messages from the
    backend. As automation events unfold during a browser session, the
    script pushes short log messages to the UI. Only the most recent
    message is shown at any time — there is no scrollback or history.

    The font size should be comparable to the task name/email for easy
    readability. Log messages support three color states:
    - **Default (white):** Normal informational messages
      (e.g., "Navigating to checkout...", "Entering payment info...")
    - **Red (#c20000):** Errors or bad outcomes
      (e.g., "Payment declined", "Session expired")
    - **Green (#0e8f00):** Final success message
      (e.g., "Order confirmed", "Account created")

    The log label is cleared when a task transitions to IDLE. When a
    task is not running, the last log message from the previous run
    remains visible until the task is started again.

    Button order (left to right):
    1. Start/Stop â€” Toggles between play and stop icons. Start launches the
       automation script; stop terminates it.
    2. View Browser / Manual Browser â€” These share the same slot:
       - View Browser (eye icon): Only visible while a task is running. Opens a
         read-only window that streams the headless browser's visual output so the
         user can observe what the automation is doing without making the browser
         headed. The user cannot interact with the page or close the browser through
         this window â€” it is purely a visual debugging/monitoring tool. The icon
         toggles to a crossed-out eye while the view window is open.
       - Manual Browser (Chrome icon): Only visible while the task is NOT running.
         Opens a fully headed browser session with all the same characteristics as
         the scripted task (same proxy, fingerprint, userdata) but without the
         automation script running. Sets the task status to MANUAL, which greys
         out the Chrome icon and shows the Stop button to close the session.
         Closing the manual browser returns the task to STOPPED status.
    3. Clone â€” Makes an identical copy of the task and appends it to the task list.
    4. Edit â€” Triggers a popup similar to the task creation dialog, but only allows
       a single profile to be selected and lets the user enter a new proxy as a
       string. The user can also set a custom status string.
    5. Delete â€” Removes the task with an inline confirmation (Yes/No).

    One key feature that we need to adjust our backend to support is preserving the
    browser userdata until a task is deleted. The user should be able to stop a browser
    and restart it later, picking up with the same cookies as before. This shouldn't be
    too complicated. It's as simple as keeping track of the userdata folder and starting
    chrome with that folder when the user clicks to start a task.

# Proxy Manager
This page allow the user to create proxy groups. The page will be formatted
the exact same way as Task Manager, but some features will be different.
One difference will be the popup that appears after the plus button. Obviously
the user will choose a name for the group. Instead of choosing a script, the user 
will choose a .txt file with one proxy per line. The user can also select which 
format his proxies are in (e.g. host:port:user:pass or user:pass@host:port).
Just like task manager, the groups can be deleted from the manager page.
The primary architectural difference between Task Manager and Proxy manager is
that the user can add proxies to a new Proxy Group upon initialization as
opposed to only being able to create tasks within the Task Group Page.
## Proxy Group Page
    This page is similar to the Task Group page. It presents the proxies in a
    similarly formatted list. The + button is still present and it will trigger
    a popup with the following options:
    1. The .txt file containing the proxies (1 per line).
    2. The format of the new proxies.
    3. An "Add Proxies" button that submits the form and appends the new
       proxies to the group.
### Proxy Container
    This represents a single proxy in a Proxy group and will be visible on the
    proxy group page. It will be formatted the same as a task container.
    The host of the proxy will act as a "name" which will be displayed on the
    far left of the container. The only other button will be on the far right,
    the delete button, which will remove a proxy from the group (no confirmation
    needed).
# Profile Manager
This page will, similarly to other managers, manage profile groups. The primary
difference is in the way groups are created. The popup will ask for the following
details in order to create a group:
1. The name of the profile group.
2. The .csv file containing the profiles.
## Profile Group Page
    This page is formatted similarly to the manager pages, with square-ish 
    containers for every Profile. There will be no + button to add more profiles.
    The only way to add profiles is by creating a new group and selecting a .csv.
### Profile Container
    This container will be shaped like an id card without a picture. It will
    display the profile name, email, and payment info, which are copied to 
    clipboard when clicked. You can also click on the profile container, which
    will trigger a popup that displays ALL information within the profile. It
    can all be clicked to copy to clipboard. There will even be an additional
    "Notes" text box where the user can leave notes on that specific profile.
    No other data besides the notes field can be edited. We'll need to go into
    more detail about the profile container when we actually start implementing
    it.

# Settings Page
This is where users can enter their AutoSolve AI API key. Eventually update checks
will be handled here too and any other global variable like paths to resources can
be configured. For now, we'll leave this page blank.



NOTES:
Certain buttons will have to be unclickable depending on the state of a task. For
example you can't edit a task while it's running. We'll discuss this kind of
thing right before the implementation of relevant components.


# Task Button Reference

Detailed specification for every action button on a TaskRow. Buttons are
rendered on the right side of each task row from left to right in the order
listed below. All icons come from the FontAwesome 5 pack (ikonli).

## 1. Start / Stop
A single button that toggles between two states.

**Start state (task is NOT running or in manual mode)**
- Icon: PLAY
- Color: #6aeb8a (green)
- Action: Launches the automation script for this task. The browser starts
  in headless mode using the task's assigned proxy, fingerprint, and
  userdata directory. On click the task status changes to RUNNING and the
  button swaps to the Stop icon.
- Enabled: Always, unless the task is already running or in manual mode.

**Stop state (task IS running or in manual mode)**
- Icon: STOP
- Color: #d15252 (red)
- Action: When RUNNING, terminates the automation script and closes the
  headless browser. When MANUAL, closes the headed manual browser session.
  In both cases the task status changes to STOPPED and the button swaps
  back to the Play icon.
- Enabled: Only while the task is running or in manual mode.

## 2. View Browser / Manual Browser (shared slot)
Two buttons occupy the same physical space. Only one is visible at a time
depending on the task's status.

### View Browser (visible only while task status is RUNNING)
- Default icon: EYE
- Active icon: EYE_SLASH (shown while the view window is open)
- Color: #389deb (blue) for both icons
- Action: Opens a read-only window that streams the headless browser's
  visual output in real time. This is a monitoring/debugging tool â€” the
  user can see exactly what the automation script is doing on the page.
  The window does NOT make the browser headed. The user cannot click any
  elements on the page, interact with form fields, or close the browser
  through this window. They can only close the view window itself.
  Clicking the button while the view window is already open closes it
  and swaps the icon back to EYE.
- Enabled: Only while the task status is RUNNING. Hidden (not just
  disabled) for all other statuses including MANUAL.

### Manual Browser (visible while task status is NOT RUNNING)
- Icon: CHROME (FontAwesomeBrands)
- Color: #a3a3a3 (grey)
- Action: Opens a fully headed (visible) browser session using the same
  proxy, fingerprint, and userdata directory as the scripted task, but
  without the automation script running. This lets the user manually
  interact with sites using the task's identity â€” useful for manual
  account setup, debugging login issues, or verifying cookies. On click
  the task status changes to MANUAL.
- Enabled: Only while the task is NOT running and NOT already in manual
  mode. Hidden when the task status is RUNNING. Visible but disabled
  (greyed out) when the task status is MANUAL â€” the Chrome icon stays
  visible to indicate a manual session is active, but cannot be clicked.
  The Stop button (slot 1) is used to close the manual browser instead.

### Shared slot behavior
- When a task transitions to RUNNING: the Manual Browser button is hidden,
  the View Browser button becomes visible, and any active manual browser
  session state is reset.
- When a task transitions to MANUAL: the View Browser button stays hidden,
  the Manual Browser button remains visible but is disabled (greyed out),
  and the Start/Stop button shows the Stop icon.
- When a task transitions from RUNNING â†’ STOPPED: the View Browser button
  is hidden, the Manual Browser button becomes visible and enabled, and
  any active view browser state is reset.
- When a task transitions from MANUAL â†’ STOPPED: the Manual Browser button
  is re-enabled (no longer greyed out) and the Start/Stop button shows the
  Play icon.

## 3. Clone
- Icon: CLONE
- Color: #389deb (blue)
- Action: Creates an identical copy of this task and appends it to the
  end of the task list within the same group. The cloned task copies the
  profile ID, proxy ID, warm session setting, and notes from the
  original. The clone starts with IDLE status, a fresh userdata
  directory, and no custom status. It is effectively a new task that
  shares the same configuration.
- Enabled: Always, regardless of task status. Cloning a running task
  does not clone the running state â€” the new task starts idle.

## 4. Edit
- Icon: EDIT
- Color: #f7c82d (yellow)
- Action: Opens an edit dialog for the task. Unlike the task creation
  dialog (which supports multi-profile selection), the edit dialog is
  scoped to a single task. The user can:
  1. Enter a new proxy as a raw string (host:port:user:pass format).
     This replaces the task's current proxy assignment.
  2. Set a custom status string (max 20 characters) that displays to the
     right of the task name/email in the row. Useful for tagging tasks
     with notes like "OTP needed" or "Verified".
  The edit dialog does NOT allow changing the profile â€” that is fixed at
  task creation time.
- Enabled: Only while the task is NOT running or in manual mode. Disabled
  (greyed out at 30% opacity) while the task is running or in manual mode
  to prevent edits while a browser session is active.

## 5. Delete
- Icon: TRASH_ALT
- Color: #d15252 (red)
- Action: Removes the task from the group permanently. Because deletion
  also removes the task's userdata directory (cookies, localStorage,
  session data), an inline confirmation is shown before proceeding.
  Clicking the trash icon replaces it with two small buttons:
  - "Yes" (red background, white text): Confirms deletion. The task
    entity is removed from the database, its userdata directory is
    deleted from disk, and the TaskRow is removed from the UI.
  - "No" (grey background): Cancels the deletion and restores the
    trash icon.
  The delete slot has a fixed width (90px) so the Yes/No buttons do
  not shift neighboring buttons when they appear.
- Enabled: Only while the task is NOT running or in manual mode. Disabled
  (greyed out at 30% opacity) while the task is running or in manual mode.
  If the task enters a running or manual state while the confirmation is
  showing, the confirmation is automatically collapsed and the trash icon
  is restored in its disabled state.

## Button State Summary

| Button         | IDLE    | RUNNING  | MANUAL   | COMPLETED | FAILED  | STOPPED |
|----------------|---------|----------|----------|-----------|---------|---------|
| Start/Stop     | PLAY    | STOP     | STOP     | PLAY      | PLAY    | PLAY    |
| View Browser   | hidden  | EYE      | hidden   | hidden    | hidden  | hidden  |
| Manual Browser | CHROME  | hidden   | disabled | CHROME    | CHROME  | CHROME  |
| Clone          | enabled | enabled  | enabled  | enabled   | enabled | enabled |
| Edit           | enabled | disabled | disabled | enabled   | enabled | enabled |
| Delete         | enabled | disabled | disabled | enabled   | enabled | enabled |
