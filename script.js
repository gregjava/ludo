/* =========================================================
   1. STATIC GAME DATA — the board's geometry
   (The board itself is now pre-rendered in HTML; these constants
   are only used to work out legal moves, captures, and where a
   token's step-count currently sits.)
   ========================================================= */
const COLORS = ['red','green','yellow','blue'];

const PATH = [
  [6,1],[6,2],[6,3],[6,4],[6,5],
  [5,6],[4,6],[3,6],[2,6],[1,6],[0,6],
  [0,7],
  [0,8],[1,8],[2,8],[3,8],[4,8],[5,8],
  [6,9],[6,10],[6,11],[6,12],[6,13],[6,14],
  [7,14],
  [8,14],[8,13],[8,12],[8,11],[8,10],[8,9],
  [9,8],[10,8],[11,8],[12,8],[13,8],[14,8],
  [14,7],
  [14,6],[13,6],[12,6],[11,6],[10,6],[9,6],
  [8,5],[8,4],[8,3],[8,2],[8,1],[8,0],
  [7,0],
  [6,0]
];

const ENTRY = { red:0, green:13, yellow:26, blue:39 };
const SAFE = new Set([0,8,13,21,26,34,39,47]);

const HOME_STRETCH = {
  red:    [[7,1],[7,2],[7,3],[7,4],[7,5],[7,6]],
  green:  [[1,7],[2,7],[3,7],[4,7],[5,7],[6,7]],
  yellow: [[7,13],[7,12],[7,11],[7,10],[7,9],[7,8]],
  blue:   [[13,7],[12,7],[11,7],[10,7],[9,7],[8,7]]
};

const HOME_ORIGIN = { red:[0,0], green:[0,9], yellow:[9,9], blue:[9,0] };
const SEED_SLOTS = [[1,1],[1,4],[4,4],[4,1]];

function seedSlotCell(color, tokenIndex){
  const [or, oc] = HOME_ORIGIN[color];
  const [sr, sc] = SEED_SLOTS[tokenIndex];
  return [or+sr, oc+sc];
}

/* =========================================================
   2. GAME STATE
   Every token's progress is a single number:
     0        -> still in the yard
     1 - 51   -> position on the shared 52-square track
     52 - 57  -> position on that colour's home stretch (57 = home!)
   ========================================================= */
let state = {
  current: 0,
  dice: null,
  sixStreak: 0,
  tokens: { red:[0,0,0,0], green:[0,0,0,0], yellow:[0,0,0,0], blue:[0,0,0,0] },
  winner: null,
  msg: ''
};

/* =========================================================
   3. HELPERS — translate a token's step count into board coords
   ========================================================= */
function cellForToken(color, steps){
  if(steps>=1 && steps<=51){
    return PATH[(ENTRY[color] + steps - 1) % 52];
  }
  if(steps>=52 && steps<=57){
    return HOME_STRETCH[color][steps-52];
  }
  return null; // steps 0 (still a seed) is handled separately via seedSlotCell()
}

function globalTrackIndex(color, steps){
  if(steps<1 || steps>51) return null;
  return (ENTRY[color] + steps - 1) % 52;
}

/* Which of a player's tokens can legally move with the given dice value? */
function movableTokens(color, dice){
  return state.tokens[color].reduce((list, steps, i)=>{
    if(steps===0){ if(dice===6) list.push(i); }
    else if(steps>0 && steps<57){ if(steps+dice<=57) list.push(i); }
    return list;
  }, []);
}

function cap(s){ return s[0].toUpperCase()+s.slice(1); }

/* =========================================================
   4. DOM REFERENCES (all elements already exist in the HTML)
   ========================================================= */
const turnLabelEl = document.getElementById('turnLabel');
const diceEl = document.getElementById('dice');
const rollBtn = document.getElementById('rollBtn');
const messageEl = document.getElementById('message');
const newGameBtn = document.getElementById('newGameBtn');
const tokenLayer = document.getElementById('tokenLayer');

const tokenEls = { red:[], green:[], yellow:[], blue:[] };
tokenLayer.querySelectorAll('.token').forEach(el=>{
  tokenEls[el.dataset.color][Number(el.dataset.index)] = el;
});

const tokBtnEls = { red:[], green:[], yellow:[], blue:[] };
document.querySelectorAll('.tokBtn').forEach(el=>{
  tokBtnEls[el.dataset.color][Number(el.dataset.index)] = el;
});

/* Swap an element's row/column classes for new ones (r1-r15 / c1-c15). */
function setGridPos(el, row, col){
  el.classList.forEach(cls=>{
    if(/^r\d+$/.test(cls) || /^c\d+$/.test(cls)) el.classList.remove(cls);
  });
  el.classList.add(`r${row+1}`, `c${col+1}`);
}

/* =========================================================
   5. RENDERING — reflect state into the (pre-built) DOM every change
   ========================================================= */
function render(){
  // ---- status area ----
  if(state.winner){
    turnLabelEl.textContent = `🏆 ${cap(state.winner)} wins!`;
    turnLabelEl.dataset.turn = 'winner';
  } else {
    turnLabelEl.textContent = `${cap(COLORS[state.current])}'s turn`;
    turnLabelEl.dataset.turn = COLORS[state.current];
  }
  diceEl.textContent = state.dice ?? '-';
  rollBtn.disabled = state.dice!==null || !!state.winner;
  messageEl.textContent = state.msg || '';

  document.querySelectorAll('.panel').forEach(p=>{
    p.classList.toggle('active', p.dataset.color===COLORS[state.current] && !state.winner);
  });

  // ---- tokens on board ----
  const movable = state.dice!==null ? movableTokens(COLORS[state.current], state.dice) : [];

  // group tokens that land on the same square so overlapping ones can be nudged apart
  const groups = new Map();
  COLORS.forEach(color=>{
    state.tokens[color].forEach((steps, i)=>{
      const rc = steps===0 ? seedSlotCell(color, i) : cellForToken(color, steps);
      if(!rc) return;
      const key = rc.join(',');
      if(!groups.has(key)) groups.set(key, []);
      groups.get(key).push({color, i});
    });
  });

  groups.forEach((list, key)=>{
    const [r,c] = key.split(',').map(Number);
    list.forEach((item, idx)=>{
      const el = tokenEls[item.color][item.i];
      setGridPos(el, r, c);

      if(list.length>1){
        el.dataset.slot = String(idx);
      } else {
        delete el.dataset.slot;
      }

      const isMovable = item.color===COLORS[state.current] && movable.includes(item.i);
      el.classList.toggle('movable', isMovable);
    });
  });

  // ---- side panel token buttons ----
  COLORS.forEach(color=>{
    state.tokens[color].forEach((steps,i)=>{
      const btn = tokBtnEls[color][i];
      btn.classList.toggle('finished', steps===57);
      const isMovable = color===COLORS[state.current] && movable.includes(i);
      btn.classList.toggle('movable', isMovable);
      btn.textContent = steps===0 ? `#${i+1} Seed` : steps===57 ? `#${i+1} Home` : `#${i+1} (${steps})`;
    });
  });
}

/* =========================================================
   6. CONTROLS — rolling the dice
   ========================================================= */
rollBtn.addEventListener('click', rollDice);
newGameBtn.addEventListener('click', newGame);

function rollDice(){
  if(state.dice!==null || state.winner) return;

  const value = 1 + Math.floor(Math.random()*6);
  state.dice = value;
  state.sixStreak = (value===6) ? state.sixStreak+1 : 0;

  if(state.sixStreak===3){
    state.msg = `Three 6s in a row — turn forfeited!`;
    state.sixStreak = 0;
    setTimeout(()=>{ state.dice=null; nextPlayer(); render(); }, 900);
    render();
    return;
  }

  const color = COLORS[state.current];
  const moves = movableTokens(color, value);
  if(moves.length===0){
    state.msg = `Rolled ${value} — no legal move.`;
    setTimeout(()=>{
      state.dice = null;
      if(value!==6) nextPlayer();
      render();
    }, 900);
  } else {
    state.msg = `Rolled ${value} — pick a highlighted token.`;
  }
  render();
}

/* =========================================================
   7. CONTROLS — moving a token (event delegation: click anywhere
   on the board tokens or the side-panel buttons)
   ========================================================= */
tokenLayer.addEventListener('click', e=>{
  const el = e.target.closest('.token.movable');
  if(el) moveToken(el.dataset.color, Number(el.dataset.index));
});

document.getElementById('side').addEventListener('click', e=>{
  const el = e.target.closest('.tokBtn.movable');
  if(el) moveToken(el.dataset.color, Number(el.dataset.index));
});

function moveToken(color, tokenIndex){
  const dice = state.dice;
  if(dice===null) return;
  if(!movableTokens(color, dice).includes(tokenIndex)) return;

  let steps = state.tokens[color][tokenIndex];
  steps = (steps===0) ? 1 : steps + dice;
  state.tokens[color][tokenIndex] = steps;

  // ---- capture check ----
  state.msg = '';
  const landedIndex = globalTrackIndex(color, steps);
  if(landedIndex!==null && !SAFE.has(landedIndex)){
    COLORS.forEach(other=>{
      if(other===color) return;
      state.tokens[other].forEach((s2, j)=>{
        if(globalTrackIndex(other, s2)===landedIndex){
          state.tokens[other][j] = 0;              // sent home
          state.msg = `${cap(color)} captured ${other}'s token #${j+1}!`;
        }
      });
    });
  }

  // ---- win check ----
  if(state.tokens[color].every(s=>s===57)){
    state.winner = color;
    state.dice = null;
    render();
    return;
  }

  if(!state.msg){
    state.msg = steps===57 ? `${cap(color)} token #${tokenIndex+1} reached home!` : '';
  }

  // rolling a 6 earns another turn (unless the forfeit rule already fired)
  const extraTurn = dice===6;
  state.dice = null;
  if(!extraTurn) nextPlayer();
  render();
}

function nextPlayer(){
  state.current = (state.current+1) % COLORS.length;
  state.sixStreak = 0;
}

/* =========================================================
   8. RESET
   ========================================================= */
function newGame(){
  state = {
    current: 0, dice: null, sixStreak: 0, winner: null, msg: '',
    tokens: { red:[0,0,0,0], green:[0,0,0,0], yellow:[0,0,0,0], blue:[0,0,0,0] }
  };
  render();
}

render();
