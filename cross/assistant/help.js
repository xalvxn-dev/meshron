// The Help page's content (Windows): how to start, every kind of command, and the hand gestures.
// Each example can be clicked to try it.
window.MeshronHelp = {
  start: [
    ['Talk to it', 'Press Ctrl+Alt+Space (or your own shortcut), click the orb, or turn on Always listening and say "Hey Meshron". Then just say what you want, the way you would to a person.'],
    ['Type instead', 'Type in the box at the bottom of this window and press Enter.'],
    ['It asks first', 'Anything risky (deleting files, sending messages) shows an Approve / Cancel box before it happens. Closing an app happens right away.'],
    ['The orb', 'Click it to talk. Drag it anywhere; drop it near an edge to dock it. Right-click opens this window.'],
    ['Stop it talking', 'Say "stop", "be quiet" or "enough" while it talks (with Always listening on), or click the orb. Saying its name interrupts and lets you speak.'],
    ['Its look', 'Settings, Look: classic orbs, faces that show feelings, island orbs and companions that walk, fly and float on your screen, plus colour themes.'],
    ['Privacy', 'Settings, Hide from screenshots keeps the chat, the orb and showcases out of screenshots and screen sharing.'],
    ['Stop everything', 'Ctrl+Alt+G stops game mode and hand control at once.']
  ],
  sections: [
    { title: 'Apps and windows', items: [
      ['open Spotify', 'Opens any installed app'],
      ['switch to Chrome', 'Brings an open window to the front'],
      ['close Notepad', 'Closes an app right away (the app itself asks if there is unsaved work)'],
      ['show the desktop', 'Minimises everything'],
      ['lock my PC', 'Locks the screen'],
      ['make a desktop shortcut for Spotify', 'Shortcuts to apps, folders or websites']
    ] },
    { title: 'Typing, clicking and the screen', items: [
      ['type hello world', 'Types into the app in front'],
      ['press ctrl+s', 'Any keyboard shortcut'],
      ['click the Send button', 'Finds a button by its name and clicks it'],
      ['scroll down', 'Scrolls the window in front'],
      ['what is on my screen?', 'Reads the screen and tells you'],
      ['show me how to change my wallpaper', 'Shows each step with its own pointer: points, circles, clicks and drags']
    ] },
    { title: 'Files and folders', items: [
      ['what is in my Downloads?', 'Lists files'],
      ['make a folder called Projects on the desktop', 'New folders'],
      ['tidy my Downloads into folders by type', 'Moves files (asks first)'],
      ['delete the old installers in Downloads', 'Deletes go to the Recycle Bin, after you approve'],
      ['summarise this document', 'Reads Word, PDF and text files']
    ] },
    { title: 'Later, at a set time', items: [
      ['at 3 PM open https://zoom.us/j/123456 in Edge and join the meeting', 'Joins a meeting at its time (use your own link)'],
      ['lock my PC at 10 PM', 'Anything it can do now, it can do at a set time'],
      ['every weekday at 9 AM open Outlook and Teams', 'Repeats daily or on weekdays'],
      ['what have you scheduled?', 'Lists them. "Cancel the Zoom one" cancels one']
    ] },
    { title: 'Time, tasks and reminders', items: [
      ['set a timer for 10 minutes', 'Timers'],
      ['remind me to call Mum at 6', 'Reminders'],
      ['add buy milk to my tasks', 'A to-do list'],
      ['what are my tasks?', 'Reads them back']
    ] },
    { title: 'Music, sound and your PC', items: [
      ['volume 40', 'Sets the volume'],
      ['mute', 'Mute or unmute'],
      ['pause', 'Play, pause, next song'],
      ['what is using my RAM?', 'A live view of what takes memory'],
      ['how is my PC doing?', 'CPU, memory and graphics'],
      ['how much space do I have?', 'Your drives'],
      ['battery', 'Battery level']
    ] },
    { title: 'The web', items: [
      ['what is the weather in Lagos?', 'Searches the web for anything current'],
      ['latest news about electric cars', 'News and facts, with dates'],
      ['open youtube.com', 'Opens a website']
    ] },
    { title: 'Show me on my screen (its own pointer)', items: [
      ['show me how to save a file in Word', 'Its hologram pointer glides to each button, circles it, shows the click, with a short note per step'],
      ['show me how to move a file into a folder', 'Shows the drag: picks the file up, a dashed path to the folder, and the drop'],
      ['show me how to change my wallpaper', 'Step by step through Settings, pointing at each thing to click'],
      ['show me how to take a screenshot', 'Points at the keys or the Snipping Tool and highlights them'],
      ['where is the recycle bin?', 'Points at it with an arrow and a note'],
      ['circle the Wi-Fi icon', 'Circles or highlights anything on your screen'],
      ['show me how to copy and paste', 'Shows selecting, the keys to press and where it goes'],
      ['save this file for me', 'Does it for you instead of showing (say "do it" when it shows you something)']
    ] },
    { title: 'Teaching and the board (the screen as a whiteboard)', items: [
      ['teach me how to solve quadratic equations', 'Writes and explains step by step, with the maths written properly'],
      ['explain photosynthesis with a diagram', 'Draws diagrams with arrows, labels and boxes'],
      ['draw a triangle and label its angles', 'Lines, circles, boxes, arrows and brackets'],
      ['plot y = x squared', 'Graphs, with points marked on them'],
      ['teach me fractions with pictures', 'Shapes cut into parts, highlighted as it explains'],
      ['explain how the heart works step by step', 'A numbered list that builds up as it talks'],
      ['clear the board', 'Clears it (or press Esc)']
    ] },
    { title: 'Pictures and drawing', items: [
      ['generate an image of a cat astronaut', 'Makes a picture with AI, saved to Pictures\\Meshron'],
      ['make a logo for my bakery', 'Logos, posters, wallpapers, art'],
      ['draw a house in Paint', 'Opens Paint and draws it with the mouse']
    ] },
    { title: 'Talking to it', items: [
      ['stop', 'While it talks: stop, be quiet or enough cuts it off. Saying its name interrupts so you can speak'],
      ['what is 15 percent of 240?', 'Quick answers, maths and facts'],
      ['plan my day', 'Uses your tasks and reminders']
    ] },
    { title: 'Your look, the orb and companions', items: [
      ['show me what you can do', 'A visual tour of every feature'],
      ['recommend a voice', 'Voices to choose from (Settings: studio voice)'],
      ['come here', 'Companions walk, fly and float on your screen and sit on your apps: come here, sit, dance, take a nap, wake up, fly, jump, hide, show']
    ] },
    { title: 'Game mode and hand control', items: [
      ['open game mode', 'Opens inside this window. Games: Hand School (learn every move), Block Drop, Block Builder (turn the plot with a swipe), Orb Pop, Face Dash, Air Keys, Mood Mirror, Fingerspell'],
      ['open game mode', 'For your real PC games, a small game orb floats over them. Click it for every move, with pictures:'],
      ['open game mode', 'Racing (Asphalt, Need for Speed, Beach Buggy): grip an air wheel and tilt it to steer, open hands to brake, pinch for nitro'],
      ['open game mode', 'Shooter (Call of Duty, Fortnite): right hand aims, pinch fires, fist reloads; left palm walks, left pinch aims down sights'],
      ['open game mode', 'Block Games (Minecraft): head looks, palm walks, punch hits, hold a fist to mine, pinch to place'],
      ['open game mode', 'Hand Control: a mouse in the air for any app']
    ] },
    { title: 'Your other devices', items: [
      ['on my phone, turn on the flashlight', 'Turn on "Control from my other devices" here and on your phone (same account). Start with "on my phone" or "on my PC"']
    ] }
  ],
  gestures: [
    ['Point (one finger)', 'Aim and move'],
    ['Pinch (thumb and index together)', 'Select, click, press a key'],
    ['Pinch and hold', 'Drag'],
    ['Two fingers up (V)', 'Scroll'],
    ['Swipe left or right', 'Back / forward, previous / next'],
    ['Hold an open palm', 'Pause (Hand Control)'],
    ['Hold a fist (1.5 s)', 'Back to the game menu; in Hand Control and Game Pad, stop'],
    ['Punch toward the camera', 'Drop a block, hit in Game Pad'],
    ['Turn your head', 'Steer in Face Dash, look around in Game Pad']
  ]
}
