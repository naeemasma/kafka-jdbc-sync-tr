create table eventMessages
(
   id integer auto_increment not null,
   description varchar(255) not null,
   primary key(id)
);

create table eventMessageDetail
(
   id integer not null,
   severity varchar(6) not null,
   primary key(id)
);