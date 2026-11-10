import SwiftUI

/// The tafs' scheduled work still to come (kiff/tafs#534): Day, Week and Month, Week first. Every
/// occurrence is tafs cloud's (`GET /v1/calendar`), placed by the day and minute it gives in the
/// account's zone; the app computes none. Read-only: a tap opens the taf.
///
/// Wide (an iPad on its side, a wide window): a month navigator at the upper left, the calendar
/// beside it, and the agenda under them. Compact (an iPad upright, a phone): a scrolling week strip,
/// the selected day's work under it. What it shows is kept on the model, so a rotation keeps it.
struct CalendarView: View {
    @EnvironmentObject private var model: AppModel
    /// Opens a taf beside the sidebar; nil on the phone, where a tap pushes the taf.
    var open: ((UUID) -> Void)? = nil

    private static let hourHeight: CGFloat = 44

    private var today: String { CalendarMath.today(in: AccountTime.zone) }
    private var state: CalendarState { model.calendarState ?? CalendarState(selected: today) }
    /// The read for the selected date's range, or nil while it's being read.
    private var read: TafsCloud.CalendarRead? { CalendarMath.current(model.calendarRead, selected: state.selected) }
    private var events: [TafsCloud.CalendarEvent] { read?.events ?? [] }
    private func show(_ s: CalendarState) { withMotion(.easeInOut(duration: 0.2)) { model.calendarState = s } }
    private func select(_ day: String) { show(CalendarState(mode: state.mode, selected: day)) }

    var body: some View {
        GeometryReader { geo in
            let layout = CalendarLayout.of(width: geo.size.width, height: geo.size.height)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if layout == .wide { wide } else { compact }
                }
                .padding(layout == .wide ? 24 : 16)
                .frame(maxWidth: 1180, alignment: .leading)
            }
            .refreshable { await model.loadCalendar(state) }
        }
        .screenBackground()
        .navigationTitle(Words.Calendar.heading)
        .navigationBarTitleDisplayMode(.inline)
        // Read again for another range, and for another account (the same month, after a switch).
        .task(id: "\(model.accountGeneration)|\(CalendarMath.range(state.selected).from)") { await model.loadCalendar(state) }
    }

    // MARK: Layouts

    private var wide: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top, spacing: 24) {
                navigator.frame(width: 280)
                VStack(alignment: .leading, spacing: 14) {
                    header(compact: false)
                    switch state.mode {
                    case .day: timeGrid([state.selected])
                    case .week: timeGrid(CalendarMath.week(state.selected))
                    case .month: monthGrid(large: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            agenda
        }
    }

    private var compact: some View {
        VStack(alignment: .leading, spacing: 14) {
            header(compact: true)
            switch state.mode {
            case .day:
                weekStrip
                timeGrid([state.selected])
            case .week:
                weekStrip
                dayList(state.selected)
            case .month:
                monthGrid(large: false)
                dayList(state.selected)
            }
        }
    }

    // MARK: Header: Today, previous and next, the date, and Day / Week / Month

    @ViewBuilder private func header(compact: Bool) -> some View {
        let nav = HStack(spacing: 10) {
            Button(Words.Calendar.today) { select(today) }
                .buttonStyle(OutlineButtonStyle())
                .disabled(state.selected == today)
                .accessibilityIdentifier("calendar.today")
            Button { show(CalendarMath.step(state, by: -1)) } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel(state.mode == .day ? Words.Calendar.previousDay : state.mode == .week ? Words.Calendar.previousWeek : Words.Calendar.previousMonth)
            Button { show(CalendarMath.step(state, by: 1)) } label: { Image(systemName: "chevron.right") }
                .accessibilityLabel(state.mode == .day ? Words.Calendar.nextDay : state.mode == .week ? Words.Calendar.nextWeek : Words.Calendar.nextMonth)
            Text(CalendarMath.title(state))
                .scaledFont(compact ? 16 : 20, weight: .bold, design: .rounded, relativeTo: .headline)
                .foregroundColor(Theme.ink)
                .oneLineUnlessLarge()
                .accessibilityIdentifier("calendar.title")
            Spacer(minLength: 0)
            DatePicker(Words.Calendar.datePicker, selection: dateBinding, displayedComponents: .date)
                .labelsHidden()
                .environment(\.timeZone, TimeZone(identifier: "UTC")!)
                .accessibilityLabel(Words.Calendar.chooseDate)
        }
        .tint(Theme.amberText)
        let picker = Picker(Words.Calendar.viewPicker, selection: Binding(get: { state.mode }, set: { show(CalendarState(mode: $0, selected: state.selected)) })) {
            ForEach(CalendarMode.allCases, id: \.self) { Text($0.title).tag($0) }
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("calendar.mode")
        if compact {
            VStack(alignment: .leading, spacing: 10) { nav; picker }
        } else {
            VStack(alignment: .leading, spacing: 10) { nav; picker.frame(maxWidth: 320) }
        }
        if let error = model.calendarError {
            HStack(spacing: 10) {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
                Button(Words.Common.tryAgain) { Task { await model.loadCalendar(state) } }
                    .buttonStyle(OutlineButtonStyle())
                    .accessibilityIdentifier("calendar.retry")
            }
        } else if read == nil {
            HStack(spacing: 8) {
                ProgressView()
                Text(Words.Calendar.reading).scaledFont(13).foregroundColor(Theme.dim)
            }
            .accessibilityIdentifier("calendar.loading")
        } else if let read, read.halted || read.more {
            // tafs cloud's words: the account is halted, or some work was left out of this read.
            Text(read.words).scaledFont(13, weight: .semibold).foregroundColor(read.halted ? Theme.no : Theme.soft)
                .accessibilityIdentifier("calendar.words")
        }
    }

    /// The selected date as a date at noon UTC, drawn in UTC: the picker shows the same day it holds.
    private var dateBinding: Binding<Date> {
        Binding(get: {
            let p = state.selected.split(separator: "-").compactMap { Int($0) }
            var c = Calendar(identifier: .gregorian)
            c.timeZone = TimeZone(identifier: "UTC")!
            return c.date(from: DateComponents(year: p.first, month: p.dropFirst().first, day: p.last, hour: 12)) ?? Date()
        }, set: { date in
            select(CalendarMath.today(in: TimeZone(identifier: "UTC")!, now: date))
        })
    }

    // MARK: The month: the navigator (wide) and the grid

    private var navigator: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(CalendarMath.title(CalendarState(mode: .month, selected: state.selected)))
                    .scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                Spacer()
                Button { select(CalendarMath.plusMonths(state.selected, -1)) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel(Words.Calendar.previousMonth)
                Button { select(CalendarMath.plusMonths(state.selected, 1)) } label: { Image(systemName: "chevron.right") }
                    .accessibilityLabel(Words.Calendar.nextMonth)
            }
            .tint(Theme.amberText)
            monthCells(large: false)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.panel))
        .accessibilityIdentifier("calendar.navigator")
    }

    private func monthGrid(large: Bool) -> some View {
        monthCells(large: large)
            .accessibilityIdentifier("calendar.month")
    }

    private func monthCells(large: Bool) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                ForEach(CalendarMath.weekdayNames, id: \.self) { name in
                    Text(name).scaledFont(11, weight: .bold).foregroundColor(Theme.dim).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
                ForEach(CalendarMath.monthGrid(state.selected), id: \.self) { day in
                    dayCell(day, large: large)
                }
            }
        }
    }

    private func dayCell(_ day: String, large: Bool) -> some View {
        let inMonth = CalendarMath.sameMonth(day, state.selected)
        let marks = CalendarMath.marks(events, on: day)
        let count = CalendarMath.count(events, on: day)
        let selected = day == state.selected
        return Button { select(day) } label: {
            VStack(spacing: 3) {
                Text("\(CalendarMath.dayNumber(day))")
                    .scaledFont(large ? 15 : 13, weight: selected ? .bold : .regular)
                    .foregroundColor(inMonth ? Theme.ink : Theme.dim.opacity(0.6))
                HStack(spacing: 2) {
                    ForEach(Array(marks.enumerated()), id: \.offset) { mark in
                        Circle().fill(Theme.tafColor(mark.element)).frame(width: 5, height: 5)
                    }
                }
                .frame(height: 5)
                if large, count > 0 {
                    Text("\(count)").scaledFont(11).foregroundColor(Theme.soft)
                }
            }
            .frame(maxWidth: .infinity, minHeight: large ? 64 : 38)
            .background(RoundedRectangle(cornerRadius: 8).fill(selected ? Theme.amber.opacity(0.18) : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(day == today ? Theme.amber : Color.clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.drivablePlain)
        .accessibilityLabel(count > 0 ? Words.Calendar.dayScheduled(count: count, day: CalendarMath.dayHeading(day)) : CalendarMath.dayHeading(day))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: The week strip (compact)

    private var weekStrip: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(CalendarMath.monthGrid(state.selected), id: \.self) { day in
                        stripCell(day).id(day)
                    }
                }
                .padding(.vertical, 2)
            }
            .onAppear { proxy.scrollTo(CalendarMath.weekStart(state.selected), anchor: .leading) }
            .onChange(of: state.selected) { day in
                withMotion(.easeInOut(duration: 0.2)) { proxy.scrollTo(CalendarMath.weekStart(day), anchor: .leading) }
            }
        }
        .accessibilityIdentifier("calendar.strip")
    }

    private func stripCell(_ day: String) -> some View {
        let selected = day == state.selected
        let marks = CalendarMath.marks(events, on: day)
        return Button { select(day) } label: {
            VStack(spacing: 4) {
                Text(CalendarMath.weekdayNames[CalendarMath.weekdayIndex(day)]).scaledFont(11, weight: .bold)
                    .foregroundColor(selected ? Theme.amberInk : Theme.dim)
                Text("\(CalendarMath.dayNumber(day))").scaledFont(17, weight: .semibold)
                    .foregroundColor(selected ? Theme.amberInk : Theme.ink)
                HStack(spacing: 2) {
                    ForEach(Array(marks.enumerated()), id: \.offset) { mark in
                        Circle().fill(selected ? Theme.amberInk : Theme.tafColor(mark.element)).frame(width: 5, height: 5)
                    }
                }
                .frame(height: 5)
            }
            .frame(width: 46, height: 66)
            .background(RoundedRectangle(cornerRadius: 12).fill(selected ? Theme.amber : Theme.panel))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(day == today && !selected ? Theme.amber : Color.clear, lineWidth: 1.5))
        }
        .buttonStyle(.drivablePlain)
        .accessibilityLabel(CalendarMath.dayHeading(day))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Time slots (Day and Week)

    private func timeGrid(_ days: [String]) -> some View {
        let byDay = Dictionary(uniqueKeysWithValues: days.map { ($0, CalendarMath.slots(events, on: $0)) })
        let first = CalendarMath.firstHour(events, days: days)
        return VStack(spacing: 0) {
            if days.count > 1 {
                HStack(spacing: 4) {
                    Color.clear.frame(width: 48, height: 1)
                    ForEach(days, id: \.self) { day in
                        Button { show(CalendarState(mode: .day, selected: day)) } label: {
                            VStack(spacing: 1) {
                                Text(CalendarMath.weekdayNames[CalendarMath.weekdayIndex(day)]).scaledFont(11, weight: .bold).foregroundColor(Theme.dim)
                                Text("\(CalendarMath.dayNumber(day))").scaledFont(15, weight: day == today ? .bold : .regular)
                                    .foregroundColor(day == today ? Theme.amberText : Theme.ink)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.drivablePlain)
                        .accessibilityLabel(CalendarMath.dayHeading(day))
                    }
                }
                .padding(.bottom, 6)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(0..<24, id: \.self) { hour in
                            HStack(alignment: .top, spacing: 4) {
                                Text(String(format: "%02d:00", hour)).scaledFont(11, design: .monospaced).foregroundColor(Theme.dim)
                                    .frame(width: 48, alignment: .leading)
                                ForEach(days, id: \.self) { day in
                                    VStack(alignment: .leading, spacing: 2) {
                                        ForEach(byDay[day]?[hour] ?? [], id: \.taf) { slot in slotChip(slot, compact: days.count > 1) }
                                    }
                                    .frame(maxWidth: .infinity, minHeight: Self.hourHeight, alignment: .topLeading)
                                    .padding(.top, 3)
                                }
                            }
                            .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
                            .id(hour)
                        }
                    }
                }
                .frame(height: 520)
                .onAppear { proxy.scrollTo(first, anchor: .top) }
                .onChange(of: first) { proxy.scrollTo($0, anchor: .top) }
            }
            if read != nil, days.allSatisfy({ CalendarMath.events(events, on: $0).isEmpty }) { empty(days.count == 1 ? Words.Calendar.nothingOnDay : Words.Calendar.nothingThisWeek) }
        }
        .accessibilityIdentifier("calendar.slots")
    }

    private func slotChip(_ slot: CalendarMath.Slot, compact: Bool) -> some View {
        opening(slot.taf) {
            HStack(spacing: 5) {
                Circle().fill(Theme.tafColor(slot.color)).frame(width: 7, height: 7)
                Text(compact ? slot.name : "\(slot.first) · \(slot.name)").scaledFont(12, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                if slot.count > 1 { Text("×\(slot.count)").scaledFont(11).foregroundColor(Theme.soft) }
            }
            .padding(.horizontal, 6).padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 6).fill(Theme.tafColor(slot.color).opacity(0.16)))
        }
        .accessibilityLabel(slot.count > 1 ? Words.Calendar.slotTimes(count: slot.count, name: slot.name, first: slot.first) : Words.Calendar.slotAt(name: slot.name, first: slot.first))
    }

    // MARK: Lists: the selected day (compact) and the agenda (wide)

    private func dayList(_ day: String) -> some View {
        let on = CalendarMath.events(events, on: day)
        return VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: CalendarMath.dayHeading(day))
            if read != nil, on.isEmpty { empty(Words.Calendar.nothingOnDay) }
            ForEach(on) { eventRow($0) }
        }
        .accessibilityIdentifier("calendar.day")
    }

    private var agenda: some View {
        let groups = CalendarMath.agenda(events, days: CalendarMath.shown(state))
        return VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: Words.Calendar.agenda)
            // tafs cloud's own words when nothing is to come, so both apps say the same.
            if let read, groups.isEmpty { empty(read.words.isEmpty ? Words.Calendar.nothingTheseDays : read.words) }
            ForEach(groups) { group in
                VStack(alignment: .leading, spacing: 6) {
                    Text(CalendarMath.dayHeading(group.day)).scaledFont(14, weight: .semibold)
                        .foregroundColor(group.day == today ? Theme.amberText : Theme.ink)
                    ForEach(group.events) { eventRow($0) }
                }
            }
        }
        .accessibilityIdentifier("calendar.agenda")
    }

    private func eventRow(_ e: TafsCloud.CalendarEvent) -> some View {
        opening(e.taf) {
            HStack(spacing: 10) {
                Text(e.time).scaledFont(13, design: .monospaced).foregroundColor(Theme.soft).frame(width: 46, alignment: .leading)
                Circle().fill(Theme.tafColor(e.color)).frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                    Text(e.words).scaledFont(12).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.panel))
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
    }

    private func empty(_ words: String) -> some View {
        Text(words).scaledFont(13).foregroundColor(Theme.dim).padding(.vertical, 6)
    }

    /// A taf's work opens the taf: beside the sidebar, or pushed on the phone. A taf this person
    /// no longer has listed opens nothing.
    @ViewBuilder private func opening<Content: View>(_ cloudTaf: String, @ViewBuilder label: () -> Content) -> some View {
        if let id = model.tafID(forCloud: cloudTaf) {
            if let open {
                Button { open(id) } label: { label() }.buttonStyle(.drivablePlain)
            } else {
                NavigationLink(value: id) { label() }.buttonStyle(.drivablePlain)
            }
        } else {
            label()
        }
    }
}
